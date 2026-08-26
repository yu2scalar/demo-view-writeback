package com.example.viewwb.view;

import com.example.viewwb.exception.CustomException;
import com.example.viewwb.meta.ViewDefinition;
import com.example.viewwb.meta.ViewDefinition.ColumnDef;
import com.example.viewwb.meta.ViewDefinition.FilterCond;
import com.example.viewwb.meta.ViewDefinition.JoinDef;
import com.example.viewwb.meta.ViewDefinition.TableRef;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * view_definition から Spark SQL(4部名)を生成する。GUI プレビューと同じ規則だが、
 * サーバー側で常に再生成し、GUI が送ってきた sql 文字列は信用しない。
 *
 * <p>plan-011: 静的 WHERE(全 view)と GROUP BY + 集計関数(集計 view = read-only)に対応。
 * 演算子・集計関数は許可リストで検証し、値はカラム型に応じてリテラル化する(SQL インジェクション対策)。
 */
@Component
public class SqlGenerator {

    private static final Map<String, String> JOIN_SQL = Map.of(
            "INNER", "INNER JOIN",
            "LEFT", "LEFT OUTER JOIN",
            "RIGHT", "RIGHT OUTER JOIN",
            "FULL", "FULL OUTER JOIN");

    /** 許可する比較演算子(値ありのもの) */
    private static final Set<String> VALUE_OPERATORS =
            Set.of("=", "<>", ">", ">=", "<", "<=", "LIKE");
    /** 許可する集計関数(Spark 集計関数。拡張はここに足す) */
    private static final Set<String> AGG_FUNCTIONS =
            Set.of("SUM", "COUNT", "AVG", "MIN", "MAX");

    /** マテリアライズ用 SELECT(キー/GROUP BY 列は visible に関係なく含める) */
    public String selectSql(ViewDefinition def) {
        boolean aggregate = def.isAggregate();
        List<ColumnDef> cols = materializedColumns(def);

        StringBuilder sql = new StringBuilder("SELECT\n");
        sql.append(String.join(",\n", cols.stream()
                .map(c -> "  " + selectExpr(c) + " AS " + c.viewColumn())
                .toList()));

        TableRef first = def.tables().get(0);
        sql.append("\nFROM ").append(first.fqn(def.catalog())).append(' ').append(first.alias());

        Set<String> joined = new HashSet<>();
        joined.add(first.alias());
        if (def.joins() != null) {
            for (JoinDef join : def.joins()) {
                String leftAlias = aliasOf(join.left());
                String rightAlias = aliasOf(join.right());
                String next = joined.contains(leftAlias) ? rightAlias : leftAlias;
                if (joined.contains(next)) {
                    continue; // 両側とも結合済み(追加条件は M1 スコープ外)
                }
                String joinSql = JOIN_SQL.get(join.type());
                if (joinSql == null) {
                    throw new CustomException("Unsupported join type: " + join.type(), 400);
                }
                TableRef table = def.table(next);
                joined.add(next);
                sql.append('\n').append(joinSql).append(' ')
                        .append(table.fqn(def.catalog())).append(' ').append(table.alias())
                        .append(" ON ").append(join.left()).append(" = ").append(join.right());
            }
        }
        List<String> unjoined = def.tables().stream()
                .map(TableRef::alias).filter(a -> !joined.contains(a)).toList();
        if (!unjoined.isEmpty()) {
            throw new CustomException("Tables without join conditions: " + unjoined, 400);
        }

        String where = whereClause(def);
        if (!where.isEmpty()) {
            sql.append("\nWHERE ").append(where);
        }

        if (aggregate) {
            List<String> groupBy = def.groupColumns().stream().map(ColumnDef::source).toList();
            if (!groupBy.isEmpty()) {
                sql.append("\nGROUP BY ").append(String.join(", ", groupBy));
            }
        }

        List<ColumnDef> sorts = def.columns().stream()
                .filter(c -> c.sortOrder() != null && c.sortOrder() > 0)
                .sorted(Comparator.comparingInt(ColumnDef::sortOrder))
                .toList();
        if (!sorts.isEmpty()) {
            sql.append("\nORDER BY ").append(String.join(", ", sorts.stream()
                    .map(c -> c.viewColumn() + " " + ("DESC".equalsIgnoreCase(c.sortDir()) ? "DESC" : "ASC"))
                    .toList()));
        }
        return sql.toString();
    }

    /** マテリアライズ対象列: 非集計は visible||isKey、集計は visible||GROUP BY 列。 */
    private List<ColumnDef> materializedColumns(ViewDefinition def) {
        if (def.isAggregate()) {
            return def.columns().stream()
                    .filter(c -> c.visible() || !c.hasAggregate())
                    .toList();
        }
        return def.columns().stream()
                .filter(c -> c.visible() || c.isKey())
                .toList();
    }

    /** SELECT 句の式: 集計列は FUNC(source)、それ以外は source。 */
    private String selectExpr(ColumnDef c) {
        if (!c.hasAggregate()) {
            return c.source();
        }
        String func = c.aggregate().toUpperCase();
        if (!AGG_FUNCTIONS.contains(func)) {
            throw new CustomException("未対応の集計関数です: " + c.aggregate()
                    + "(対応: " + AGG_FUNCTIONS + ")", 400);
        }
        // COUNT(*) は source の列部を "*" にした場合に対応
        String col = c.source().substring(c.source().indexOf('.') + 1);
        if ("COUNT".equals(func) && "*".equals(col)) {
            return "COUNT(*)";
        }
        return func + "(" + c.source() + ")";
    }

    /** WHERE 句(filters を AND 結合)。フィルタが無ければ空文字。 */
    private String whereClause(ViewDefinition def) {
        if (def.filters() == null || def.filters().isEmpty()) {
            return "";
        }
        return String.join(" AND ", def.filters().stream().map(this::condSql).toList());
    }

    private String condSql(FilterCond f) {
        String op = f.operator() == null ? "" : f.operator().trim().toUpperCase();
        String ref = f.source();
        switch (op) {
            case "IS NULL":
                return ref + " IS NULL";
            case "IS NOT NULL":
                return ref + " IS NOT NULL";
            case "IN": {
                if (f.values() == null || f.values().isEmpty()) {
                    throw new CustomException("IN 条件には値が 1 つ以上必要です: " + ref, 400);
                }
                List<String> lits = f.values().stream().map(v -> literal(f.kind(), v)).toList();
                return ref + " IN (" + String.join(", ", lits) + ")";
            }
            default:
                if (!VALUE_OPERATORS.contains(op)) {
                    throw new CustomException("未対応の演算子です: " + f.operator(), 400);
                }
                return ref + " " + op + " " + literal(f.kind(), f.value());
        }
    }

    /** 型に応じたリテラル化。数値は検証して無引用、真偽は true/false、他は単一引用符エスケープ。 */
    private String literal(String kind, String value) {
        if (value == null) {
            return "NULL";
        }
        String k = kind == null ? "TEXT" : kind.toUpperCase();
        switch (k) {
            case "INT":
            case "BIGINT":
            case "FLOAT":
            case "DOUBLE":
                if (!value.matches("[-+]?[0-9]+(\\.[0-9]+)?")) {
                    throw new CustomException("数値列のフィルタ値が数値ではありません: " + value, 400);
                }
                return value;
            case "BOOLEAN":
                return Boolean.parseBoolean(value) ? "true" : "false";
            default:
                return "'" + value.replace("'", "''") + "'";
        }
    }

    private String aliasOf(String ref) {
        return ref.substring(0, ref.indexOf('.'));
    }
}
