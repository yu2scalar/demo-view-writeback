package com.example.viewwb.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC 経由で PK を引く {@link KeyResolver} の共通実装。接続情報の組み立てと
 * PreparedStatement 実行を担い、サブクラスは JDBC URL と PK 取得 SQL のみを提供する。
 *
 * <p>接続はこのメソッド内で開いて閉じる短命接続(カタログ直読の AnalyticsCatalogClient と同方針)。
 * password は provider_payload_json に平文で入る前提(デモ範囲)。
 */
public abstract class JdbcKeyResolver implements KeyResolver {

    private static final Logger log = LoggerFactory.getLogger(JdbcKeyResolver.class);

    /** payload から JDBC URL を組み立てる(例: jdbc:postgresql://host:port/database)。 */
    protected abstract String jdbcUrl(String host, int port, String database);

    /**
     * PK 列を ordinal 順で返す SQL。プレースホルダは (schema, table) の 2 つを ? で受ける。
     * 1 列目に列名を SELECT すること。
     */
    protected abstract String primaryKeySql();

    @Override
    public List<String> primaryKeyColumns(JsonNode payload, String schema, String table) {
        String host = payload.path("host").asText(null);
        int port = payload.path("port").asInt(0);
        String database = payload.path("database").asText(null);
        String user = payload.path("username").asText(null);
        String password = payload.path("password").asText("");
        if (host == null || port == 0 || database == null) {
            log.warn("[key-resolve] incomplete connection payload (host/port/database missing) for {}.{}",
                    schema, table);
            return List.of();
        }

        String url = jdbcUrl(host, port, database);
        List<String> keys = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = conn.prepareStatement(primaryKeySql())) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString(1));
                }
            }
        } catch (Exception e) {
            // 接続失敗・権限不足などは手動フォールバックに委ねる(builder を止めない)
            log.warn("[key-resolve] failed to read PK of {}.{} via {}: {}", schema, table, url, e.getMessage());
            return List.of();
        }
        if (keys.isEmpty()) {
            log.info("[key-resolve] no primary key found for {}.{} (fallback to manual)", schema, table);
        }
        return keys;
    }
}
