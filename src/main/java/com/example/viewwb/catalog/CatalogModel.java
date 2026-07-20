package com.example.viewwb.catalog;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * ScalarDB Analytics カタログの階層(catalog → data_source → namespace → table → column)を
 * アプリ側で扱うためのモデル。
 *
 * カタログ DB(PostgreSQL)の registry_* テーブルから直接読み出して構築する。
 * 注意: カタログにはパーティションキー/クラスタリングキーの情報は存在しない(FC-1 で確認済み)。
 * キー情報が必要な場合は provider_type=scalardb のデータソースに対して
 * ScalarDB の getTableMetadata() を併用する。
 */
public final class CatalogModel {

    public record ColumnInfo(
            String columnId,
            String name,
            String kind,          // ScalarDB 型: INT / BIGINT / TEXT / BOOLEAN / DOUBLE / FLOAT / BLOB / DATE / TIME / TIMESTAMP / TIMESTAMPTZ
            int ordinalPosition,
            boolean nullable) {
    }

    public record TableInfo(
            String tableId,
            String name,
            List<ColumnInfo> columns) {
    }

    public record NamespaceInfo(
            String namespaceId,
            List<String> names,   // カタログ上は JSON 配列(多段 namespace 対応)
            List<TableInfo> tables) {

        public String displayName() {
            return String.join(".", names);
        }
    }

    public record DataSourceInfo(
            String dataSourceId,
            String name,
            String providerType,  // "scalardb" / "mysql" / "postgresql" / ...
            JsonNode providerPayload,
            List<NamespaceInfo> namespaces) {

        public boolean isScalarDb() {
            return "scalardb".equalsIgnoreCase(providerType);
        }
    }

    public record CatalogInfo(
            String catalogId,
            String name,
            List<DataSourceInfo> dataSources) {
    }

    private CatalogModel() {
    }
}
