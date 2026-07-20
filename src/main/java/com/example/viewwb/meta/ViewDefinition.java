package com.example.viewwb.meta;

import com.example.viewwb.exception.CustomException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * View 定義。GUI(View ビルダー)が出力する view_definition JSON と 1:1 の構造で、
 * viewmgr.view_def に JSON のまま保存される(正規化しない)。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ViewDefinition(
        String viewName,
        String catalog,
        List<TableRef> tables,
        List<JoinDef> joins,
        List<ColumnDef> columns) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableRef(
            String alias,
            String dataSource,
            String providerType,   // scalardb / mysql / ...
            String namespace,
            String table,
            List<String> keyColumns) {

        public boolean isScalarDb() {
            return "scalardb".equalsIgnoreCase(providerType);
        }

        public String fqn(String catalog) {
            return catalog + "." + dataSource + "." + namespace + "." + table;
        }
    }

    /** left / right は "alias.column" 形式 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JoinDef(String type, String left, String right) {
    }

    /**
     * 選択値(ルックアップ)設定(plan-008、MS Access のルックアップフィールド相当)。
     * クエリ・更新 Tx には影響しない表示/入力補助メタデータ。
     * ソースは Cluster 経由で読める範囲(ScalarDB 管理テーブル + view 実体 views.*)。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LookupDef(
            String namespace,
            String table,
            String keyColumn,     // 選択値(view 列へ書かれるキー)
            String labelColumn) { // 表示ラベル
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ColumnDef(
            String viewColumn,
            String source,        // "alias.column"
            String kind,          // ScalarDB 型名(INT/TEXT/...)
            boolean isKey,
            boolean visible,
            boolean updatable,
            Integer sortOrder,    // null = 未指定
            String sortDir,       // ASC / DESC(sortOrder 指定時のみ)
            String via,           // TX / RE
            LookupDef lookup) {   // null = 選択値なし

        public String sourceAlias() {
            return source.substring(0, source.indexOf('.'));
        }

        public String sourceColumn() {
            return source.substring(source.indexOf('.') + 1);
        }
    }

    public TableRef table(String alias) {
        return tables.stream().filter(t -> t.alias().equals(alias)).findFirst()
                .orElseThrow(() -> new CustomException(
                        "Unknown table alias '" + alias + "' in view " + viewName, 400));
    }

    public ColumnDef column(String viewColumn) {
        return columns.stream().filter(c -> c.viewColumn().equals(viewColumn)).findFirst()
                .orElseThrow(() -> new CustomException(
                        "Unknown view column '" + viewColumn + "' in view " + viewName, 400));
    }

    /** View 実体のキー列(定義順) */
    public List<ColumnDef> keyColumns() {
        return columns.stream().filter(ColumnDef::isKey).toList();
    }

    /** alias の内部連結キー列名(plan-005: View 実体の PK を構成する非表示 TEXT 列) */
    public static String pkColumn(String alias) {
        return alias + "_pk";
    }

    /** 全テーブルの内部連結キー列名(テーブル配置順 = 実体 PK の列順) */
    public List<String> pkColumns() {
        return tables.stream().map(t -> pkColumn(t.alias())).toList();
    }

    /**
     * alias のソースキー列(TableRef.keyColumns 順)に対応する view 列。
     * <alias>_pk の値は必ずこの順で連結する(KeyConcat)。
     */
    public List<ColumnDef> keyColumnsOf(String alias) {
        TableRef tableRef = table(alias);
        return tableRef.keyColumns().stream()
                .map(sourceColumn -> columns.stream()
                        .filter(c -> c.isKey() && c.sourceAlias().equals(alias)
                                && c.sourceColumn().equals(sourceColumn))
                        .findFirst()
                        .orElseThrow(() -> new CustomException("テーブル '" + alias + "' のキー列 '"
                                + sourceColumn + "' が view のキー列に含まれていません", 400)))
                .toList();
    }

    /** 基本検証(GUI 外からの API 直叩きにも耐える) */
    public void validate() {
        if (viewName == null || !viewName.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new CustomException("viewName must match [a-z][a-z0-9_]{0,63}: " + viewName, 400);
        }
        if (tables == null || tables.isEmpty()) {
            throw new CustomException("view must reference at least one table", 400);
        }
        if (columns == null || columns.isEmpty()) {
            throw new CustomException("view must define at least one column", 400);
        }
        if (keyColumns().isEmpty()) {
            throw new CustomException("view must contain key columns", 400);
        }
        long distinctNames = columns.stream().map(ColumnDef::viewColumn).distinct().count();
        if (distinctNames != columns.size()) {
            throw new CustomException("View 列名が重複しています", 400);
        }
        for (ColumnDef c : columns) {
            if (!c.source().contains(".")) {
                throw new CustomException("column source must be alias.column: " + c.source(), 400);
            }
            table(c.sourceAlias());
            if (c.isKey() && c.updatable()) {
                throw new CustomException("key column cannot be updatable: " + c.viewColumn(), 400);
            }
            if (c.lookup() != null) {
                LookupDef lu = c.lookup();
                if (isBlank(lu.namespace()) || isBlank(lu.table())
                        || isBlank(lu.keyColumn()) || isBlank(lu.labelColumn())) {
                    throw new CustomException("列 '" + c.viewColumn() + "' の lookup には"
                            + " namespace / table / keyColumn / labelColumn が必要です", 400);
                }
            }
        }
        if (tables.size() > 1 && (joins == null || joins.isEmpty())) {
            throw new CustomException("multi-table view requires join definitions", 400);
        }
        for (TableRef t : tables) {
            if (t.keyColumns() == null || t.keyColumns().isEmpty()) {
                throw new CustomException("テーブル '" + t.alias() + "' に keyColumns がありません", 400);
            }
            keyColumnsOf(t.alias()); // 全ソースキー列が view に含まれることの検証
            String reserved = pkColumn(t.alias());
            if (columns.stream().anyMatch(c -> c.viewColumn().equals(reserved))) {
                throw new CustomException("View 列名 '" + reserved
                        + "' は内部キー列として予約されています", 400);
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
