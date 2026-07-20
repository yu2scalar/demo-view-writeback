package com.example.viewwb.view;

import com.example.viewwb.exception.CustomException;
import com.example.viewwb.meta.ViewDefinition;
import com.example.viewwb.meta.ViewDefinition.ColumnDef;
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
 */
@Component
public class SqlGenerator {

    private static final Map<String, String> JOIN_SQL = Map.of(
            "INNER", "INNER JOIN",
            "LEFT", "LEFT OUTER JOIN",
            "RIGHT", "RIGHT OUTER JOIN",
            "FULL", "FULL OUTER JOIN");

    /** マテリアライズ用 SELECT(キー列は visible に関係なく含める) */
    public String selectSql(ViewDefinition def) {
        List<ColumnDef> cols = def.columns().stream()
                .filter(c -> c.visible() || c.isKey())
                .toList();
        StringBuilder sql = new StringBuilder("SELECT\n");
        sql.append(String.join(",\n", cols.stream()
                .map(c -> "  " + c.source() + " AS " + c.viewColumn())
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

    private String aliasOf(String ref) {
        return ref.substring(0, ref.indexOf('.'));
    }
}
