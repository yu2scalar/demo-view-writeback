package com.example.viewwb.catalog;

import com.example.viewwb.catalog.CatalogModel.CatalogInfo;
import com.example.viewwb.catalog.CatalogModel.ColumnInfo;
import com.example.viewwb.catalog.CatalogModel.DataSourceInfo;
import com.example.viewwb.catalog.CatalogModel.NamespaceInfo;
import com.example.viewwb.catalog.CatalogModel.TableInfo;
import com.example.viewwb.exception.CustomException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Analytics カタログへの唯一の窓口。カタログ DB 直読(AnalyticsCatalogClient)の結果を
 * キャッシュし、GUI 向けツリー(パスワードマスク済み)と ScalarDB 接続 Properties を提供する。
 * カタログスキーマへの依存は catalog パッケージに閉じる(SDK 公開時の差し替え点)。
 */
@Service
public class CatalogService {

    private final AnalyticsCatalogClient client;
    private final AtomicReference<List<CatalogInfo>> cache = new AtomicReference<>();

    public CatalogService(
            @Value("${app.catalog.jdbc-url}") String jdbcUrl,
            @Value("${app.catalog.username}") String username,
            @Value("${app.catalog.password}") String password) {
        this.client = new AnalyticsCatalogClient(jdbcUrl, username, password);
    }

    /** カタログ全階層(キャッシュ)。reload=true で再読込 */
    public List<CatalogInfo> catalogs(boolean reload) {
        List<CatalogInfo> current = cache.get();
        if (current == null || reload) {
            try {
                current = client.readAll();
            } catch (SQLException e) {
                throw new CustomException("Analytics カタログの読み取りに失敗しました: " + e.getMessage(), e, 502);
            }
            cache.set(current);
        }
        return current;
    }

    /**
     * 指定 datasource をカタログから引く(接続情報 = provider_payload_json を保持する内部モデル)。
     * tree() のパスワードマスクは通さない。plan-010 のキー自動解決で接続情報を使うため。
     * 見つからなければ null。
     */
    public DataSourceInfo dataSource(String name) {
        if (name == null) {
            return null;
        }
        for (CatalogInfo c : catalogs(false)) {
            for (DataSourceInfo ds : c.dataSources()) {
                if (ds.name().equals(name)) {
                    return ds;
                }
            }
        }
        return null;
    }

    /** 指定 datasource / namespace / table のカタログ定義を返す(無ければ 404) */
    public TableInfo table(String dataSource, String namespace, String table) {
        for (CatalogInfo c : catalogs(false)) {
            for (DataSourceInfo ds : c.dataSources()) {
                if (!ds.name().equals(dataSource)) {
                    continue;
                }
                for (NamespaceInfo ns : ds.namespaces()) {
                    if (!ns.displayName().equals(namespace)) {
                        continue;
                    }
                    for (TableInfo t : ns.tables()) {
                        if (t.name().equals(table)) {
                            return t;
                        }
                    }
                }
            }
        }
        throw new CustomException("Table not found in catalog: "
                + dataSource + "." + namespace + "." + table, 404);
    }

    /** GUI 左ペイン用ツリー(接続情報は一切含めない) */
    public List<Map<String, Object>> tree() {
        return catalogs(false).stream().map(c -> Map.<String, Object>of(
                "catalog", c.name(),
                "dataSources", c.dataSources().stream().map(ds -> Map.of(
                        "name", ds.name(),
                        "providerType", ds.providerType(),
                        "scalardb", ds.isScalarDb(),
                        "namespaces", ds.namespaces().stream().map(ns -> Map.of(
                                "name", ns.displayName(),
                                "tables", ns.tables().stream().map(this::tableNode).toList()
                        )).toList()
                )).toList()
        )).toList();
    }

    private Map<String, Object> tableNode(TableInfo t) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", t.name());
        node.put("columns", t.columns().stream().map(this::columnNode).toList());
        return node;
    }

    private Map<String, Object> columnNode(ColumnInfo c) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", c.name());
        node.put("kind", c.kind());
        node.put("nullable", c.nullable());
        return node;
    }
}
