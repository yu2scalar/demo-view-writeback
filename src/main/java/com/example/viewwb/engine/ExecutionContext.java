package com.example.viewwb.engine;

import com.example.viewwb.exception.CustomException;
import com.example.viewwb.meta.ViewDefinition;
import com.example.viewwb.meta.ViewDefinition.TableRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 更新モジュールの実行コンテキスト。フローが見える世界はここにある型付きモデルだけ
 * (生 JSON はエンジン入口で消化済み)。
 *
 * - $input.*        受信した更新後 view 行(view 列名)
 * - before.*        View キャッシュ上の現在行(view 列名)
 * - after.<alias>.* 更新対象テーブルごとの After モデル(ソース列名。dirty なテーブル分のみ、
 *                   フローの update ノードでオンデマンド生成も可)
 * - changed.<alias> / changed.<alias>.<col>  差分フラグ(view 列はソース列名でも引ける)
 * - <var>.*         read ノードが読んだレコード / var ノードのスカラー
 */
public class ExecutionContext {

    /** テーブル1つ分の After モデル: ソース列名 → 値。キー列は読み取り専用 */
    public static class AfterModel {
        final TableRef table;
        final Map<String, Object> values = new LinkedHashMap<>();
        final Set<String> keyColumns = new LinkedHashSet<>();
        /** 書き込むべき列(入力差分 + フローが set した列) */
        final Set<String> touched = new LinkedHashSet<>();
        /**
         * フローが書き込める列 = この alias の View 参加列(非キー)全部。
         * 「更新可」は GUI 直接編集の可否であり、フロー(管理者定義のコントラクト)は
         * 更新可宣言のない列も更新できる(例: 受注数の変更に応じた在庫の差分反映。
         * 2026-07-18 ユーザー合意)。View に現れない列はタイポ防止のため引き続き不可
         */
        final Set<String> writable = new LinkedHashSet<>();

        AfterModel(TableRef table) {
            this.table = table;
        }

        public TableRef table() {
            return table;
        }

        public Map<String, Object> values() {
            return values;
        }

        public Set<String> touched() {
            return touched;
        }

        public Map<String, Object> keyValues() {
            Map<String, Object> keys = new LinkedHashMap<>();
            keyColumns.forEach(k -> keys.put(k, values.get(k)));
            return keys;
        }

        void set(String column, Object value, boolean fromFlow) {
            if (keyColumns.contains(column)) {
                throw new CustomException("キー列 '" + column + "' ("
                        + table.namespace() + "." + table.table() + ") は変更できません", 400);
            }
            if (!values.containsKey(column) && !writable.contains(column)) {
                throw new CustomException("列 '" + column + "' は " + table.alias()
                        + " の View 参加列にありません(参加列のみフローから更新できます)", 400);
            }
            values.put(column, value);
            touched.add(column);
        }

        void bindKey(String column, Object value) {
            values.put(column, value);
            keyColumns.add(column);
        }

        void allowWrite(String column) {
            writable.add(column);
        }

        void initValue(String column, Object value, boolean changed) {
            values.put(column, value);
            if (changed) {
                touched.add(column);
            }
        }
    }

    /**
     * update ノードの key 指定による直接更新(plan-007)。AfterModel(1 alias = 1 行、
     * キー = before 値固定)では書けない「同一テーブルの別の行」や View 非参加テーブルを
     * 対象にする。commit 時の一括適用で同一 Tx のまま repo.update される。
     * View 参加テーブルに使った場合は実体行への伝播対象外(用途は非参加テーブル想定)。
     */
    public record KeyedUpdate(String namespace, String table,
                              Map<String, Object> keyValues, Map<String, Object> changes) {
    }

    private final ViewDefinition def;
    private final Map<String, Object> input;
    private final Map<String, Object> before;
    private final Map<String, AfterModel> afterModels = new LinkedHashMap<>();
    private final List<KeyedUpdate> keyedUpdates = new ArrayList<>();
    private final Set<String> changedViewColumns;
    private final Map<String, Object> variables = new LinkedHashMap<>();
    private Boolean lastCompare;

    public ExecutionContext(ViewDefinition def, Map<String, Object> input,
                            Map<String, Object> before, Set<String> changedViewColumns) {
        this.def = def;
        this.input = input;
        this.before = before;
        this.changedViewColumns = changedViewColumns;
    }

    public ViewDefinition def() {
        return def;
    }

    public Map<String, Object> input() {
        return input;
    }

    public Map<String, Object> before() {
        return before;
    }

    public Set<String> changedViewColumns() {
        return changedViewColumns;
    }

    public Map<String, AfterModel> afterModels() {
        return afterModels;
    }

    public AfterModel afterModel(String alias) {
        return afterModels.get(alias);
    }

    public void putAfterModel(String alias, AfterModel model) {
        afterModels.put(alias, model);
    }

    public List<KeyedUpdate> keyedUpdates() {
        return keyedUpdates;
    }

    public void addKeyedUpdate(KeyedUpdate update) {
        keyedUpdates.add(update);
    }

    public void putVariable(String name, Object value) {
        variables.put(name, value);
    }

    public void setLastCompare(boolean result) {
        this.lastCompare = result;
    }

    public boolean lastCompare() {
        if (lastCompare == null) {
            throw new CustomException("Branch node reached before any compare node", 400);
        }
        return lastCompare;
    }

    /**
     * 参照解決: "$input.col" / "before.col" / "after.alias.col" /
     * "changed.alias" / "changed.alias.col" / "var" / "var.col" / 整数リテラル
     */
    public Object resolve(String ref) {
        String trimmed = ref.trim();
        if (trimmed.matches("-?\\d+")) {
            return Long.parseLong(trimmed);
        }
        String[] parts = trimmed.split("\\.");
        return switch (parts[0]) {
            case "$input" -> valueOf(input, part(trimmed, parts, 1), "$input");
            case "before" -> valueOf(before, part(trimmed, parts, 1), "before");
            case "after" -> {
                AfterModel model = requireModel(part(trimmed, parts, 1));
                yield valueOf(model.values(), part(trimmed, parts, 2), trimmed);
            }
            case "changed" -> resolveChanged(trimmed, parts);
            default -> resolveVariable(trimmed, parts);
        };
    }

    private Object resolveChanged(String ref, String[] parts) {
        String alias = part(ref, parts, 1);
        if (parts.length == 2) {
            return changedViewColumns.stream().anyMatch(vc ->
                    def.column(vc).sourceAlias().equals(alias));
        }
        String col = part(ref, parts, 2);
        return changedViewColumns.stream().anyMatch(vc -> {
            var c = def.column(vc);
            return c.sourceAlias().equals(alias)
                    && (c.sourceColumn().equals(col) || vc.equals(col));
        });
    }

    private Object resolveVariable(String ref, String[] parts) {
        Object value = variables.get(parts[0]);
        if (value == null && !variables.containsKey(parts[0])) {
            throw new CustomException("Unknown reference '" + ref + "'", 400);
        }
        if (parts.length == 1) {
            return value;
        }
        if (!(value instanceof Map<?, ?> model)) {
            throw new CustomException("Variable '" + parts[0] + "' is not a record; cannot access '"
                    + ref + "'", 400);
        }
        return valueOf(asStringMap(model), part(ref, parts, 1), ref);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asStringMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private Object valueOf(Map<String, Object> map, String column, String context) {
        if (!map.containsKey(column)) {
            throw new CustomException("Unknown column '" + column + "' in reference '"
                    + context + "'", 400);
        }
        return map.get(column);
    }

    private AfterModel requireModel(String alias) {
        AfterModel model = afterModels.get(alias);
        if (model == null) {
            throw new CustomException("No after model for alias '" + alias
                    + "' (table has no changes)", 400);
        }
        return model;
    }

    private String part(String ref, String[] parts, int index) {
        if (parts.length <= index) {
            throw new CustomException("Malformed reference '" + ref + "'", 400);
        }
        return parts[index];
    }
}
