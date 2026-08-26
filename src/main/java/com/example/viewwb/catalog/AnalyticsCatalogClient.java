package com.example.viewwb.catalog;

import com.example.viewwb.catalog.CatalogModel.CatalogInfo;
import com.example.viewwb.catalog.CatalogModel.ColumnInfo;
import com.example.viewwb.catalog.CatalogModel.DataSourceInfo;
import com.example.viewwb.catalog.CatalogModel.NamespaceInfo;
import com.example.viewwb.catalog.CatalogModel.TableInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ScalarDB Analytics のカタログ DB(PostgreSQL)を直接読み、カタログ階層を再構成するクライアント。
 *
 * 前提・制約(FC-1 の整理):
 * - Analytics の SDK が未公開のため、内部スキーマ(scalardb_analytics スキーマの registry_* テーブル)を
 *   直読する。スキーマは内部実装であり Analytics のバージョンアップで変わり得るため、
 *   依存はこのクラスに閉じ込める(将来 SDK 公開時にここだけ差し替える)。
 * - カタログテーブル自体が ScalarDB(Consensus Commit)管理下にあり tx_state / before_* 列を持つ。
 *   直読では未コミットの中間状態を見る可能性があるため、tx_state=COMMITTED(3) 以外の行は
 *   before イメージ(before_tx_state=3 のもの)へフォールバックする。
 */
public class AnalyticsCatalogClient {

    /** Consensus Commit の COMMITTED 状態 */
    private static final int TX_COMMITTED = 3;

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnalyticsCatalogClient(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    /** カタログ全階層を読み出す。 */
    public List<CatalogInfo> readAll() throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            Map<String, List<ColumnInfo>> columnsByTable = readColumns(conn);
            Map<String, List<TableInfo>> tablesByNamespace = readTables(conn, columnsByTable);
            Map<String, List<NamespaceInfo>> namespacesByDataSource = readNamespaces(conn, tablesByNamespace);
            Map<String, List<DataSourceInfo>> dataSourcesByCatalog = readDataSources(conn, namespacesByDataSource);
            return readCatalogs(conn, dataSourcesByCatalog);
        }
    }

    private Map<String, List<ColumnInfo>> readColumns(Connection conn) throws SQLException {
        Map<String, List<ColumnInfo>> result = new HashMap<>();
        String sql = "SELECT column_id, table_id, name, type, ordinal_position, nullable,"
                + " tx_state, before_tx_state, before_table_id, before_name, before_type,"
                + " before_ordinal_position, before_nullable"
                + " FROM scalardb_analytics.registry_columns";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                boolean committed = isCommitted(rs);
                if (!committed && !beforeCommitted(rs)) {
                    continue; // 中間状態かつ committed な before イメージも無い行は無視
                }
                String prefix = committed ? "" : "before_";
                String tableId = rs.getString(prefix + "table_id");
                ColumnInfo col = new ColumnInfo(
                        rs.getString("column_id"),
                        rs.getString(prefix + "name"),
                        parseKind(rs.getString(prefix + "type")),
                        rs.getInt(prefix + "ordinal_position"),
                        rs.getBoolean(prefix + "nullable"));
                result.computeIfAbsent(tableId, k -> new ArrayList<>()).add(col);
            }
        }
        result.values().forEach(list -> list.sort(Comparator.comparingInt(ColumnInfo::ordinalPosition)));
        return result;
    }

    private Map<String, List<TableInfo>> readTables(
            Connection conn, Map<String, List<ColumnInfo>> columnsByTable) throws SQLException {
        Map<String, List<TableInfo>> result = new HashMap<>();
        String sql = "SELECT table_id, namespace_id, name, tx_state, before_tx_state,"
                + " before_namespace_id, before_name"
                + " FROM scalardb_analytics.registry_tables";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                boolean committed = isCommitted(rs);
                if (!committed && !beforeCommitted(rs)) {
                    continue;
                }
                String prefix = committed ? "" : "before_";
                String tableId = rs.getString("table_id");
                String namespaceId = rs.getString(prefix + "namespace_id");
                TableInfo table = new TableInfo(
                        tableId,
                        rs.getString(prefix + "name"),
                        columnsByTable.getOrDefault(tableId, List.of()));
                result.computeIfAbsent(namespaceId, k -> new ArrayList<>()).add(table);
            }
        }
        result.values().forEach(list -> list.sort(Comparator.comparing(TableInfo::name)));
        return result;
    }

    private Map<String, List<NamespaceInfo>> readNamespaces(
            Connection conn, Map<String, List<TableInfo>> tablesByNamespace) throws SQLException {
        Map<String, List<NamespaceInfo>> result = new HashMap<>();
        String sql = "SELECT namespace_id, data_source_id, names, tx_state, before_tx_state,"
                + " before_data_source_id, before_names"
                + " FROM scalardb_analytics.registry_namespaces";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                boolean committed = isCommitted(rs);
                if (!committed && !beforeCommitted(rs)) {
                    continue;
                }
                String prefix = committed ? "" : "before_";
                String namespaceId = rs.getString("namespace_id");
                String dataSourceId = rs.getString(prefix + "data_source_id");
                NamespaceInfo ns = new NamespaceInfo(
                        namespaceId,
                        parseNames(rs.getString(prefix + "names")),
                        tablesByNamespace.getOrDefault(namespaceId, List.of()));
                result.computeIfAbsent(dataSourceId, k -> new ArrayList<>()).add(ns);
            }
        }
        result.values().forEach(list -> list.sort(Comparator.comparing(NamespaceInfo::displayName)));
        return result;
    }

    private Map<String, List<DataSourceInfo>> readDataSources(
            Connection conn, Map<String, List<NamespaceInfo>> namespacesByDataSource) throws SQLException {
        Map<String, List<DataSourceInfo>> result = new HashMap<>();
        String sql = "SELECT data_source_id, catalog_id, name, provider_type, provider_payload_json,"
                + " tx_state, before_tx_state, before_catalog_id, before_name, before_provider_type,"
                + " before_provider_payload_json"
                + " FROM scalardb_analytics.registry_data_sources";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                boolean committed = isCommitted(rs);
                if (!committed && !beforeCommitted(rs)) {
                    continue;
                }
                String prefix = committed ? "" : "before_";
                String dataSourceId = rs.getString("data_source_id");
                String catalogId = rs.getString(prefix + "catalog_id");
                DataSourceInfo ds = new DataSourceInfo(
                        dataSourceId,
                        rs.getString(prefix + "name"),
                        rs.getString(prefix + "provider_type"),
                        parseJson(rs.getString(prefix + "provider_payload_json")),
                        namespacesByDataSource.getOrDefault(dataSourceId, List.of()));
                result.computeIfAbsent(catalogId, k -> new ArrayList<>()).add(ds);
            }
        }
        return result;
    }

    private List<CatalogInfo> readCatalogs(
            Connection conn, Map<String, List<DataSourceInfo>> dataSourcesByCatalog) throws SQLException {
        List<CatalogInfo> result = new ArrayList<>();
        String sql = "SELECT catalog_id, name, tx_state, before_tx_state, before_name"
                + " FROM scalardb_analytics.catalogs";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                boolean committed = isCommitted(rs);
                if (!committed && !beforeCommitted(rs)) {
                    continue;
                }
                String prefix = committed ? "" : "before_";
                String catalogId = rs.getString("catalog_id");
                result.add(new CatalogInfo(
                        catalogId,
                        rs.getString(prefix + "name"),
                        dataSourcesByCatalog.getOrDefault(catalogId, List.of())));
            }
        }
        return result;
    }

    /** tx_state が NULL(非管理)または COMMITTED なら現イメージを採用してよい */
    private boolean isCommitted(ResultSet rs) throws SQLException {
        int state = rs.getInt("tx_state");
        return rs.wasNull() || state == TX_COMMITTED;
    }

    private boolean beforeCommitted(ResultSet rs) throws SQLException {
        int state = rs.getInt("before_tx_state");
        return !rs.wasNull() && state == TX_COMMITTED;
    }

    /** type 列は {"kind":"INT"} 形式の JSON */
    private String parseKind(String typeJson) {
        try {
            JsonNode node = mapper.readTree(typeJson);
            return node.path("kind").asText(typeJson);
        } catch (Exception e) {
            return typeJson;
        }
    }

    /** names 列は ["ns_mysql"] 形式の JSON 配列(多段 namespace 対応) */
    private List<String> parseNames(String namesJson) {
        try {
            return mapper.readValue(namesJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of(namesJson);
        }
    }

    private JsonNode parseJson(String json) {
        try {
            return json == null ? mapper.nullNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.getNodeFactory().textNode(json);
        }
    }
}
