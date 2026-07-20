package com.example.viewwb.engine;

import com.example.viewwb.core.DynamicRepository;
import com.example.viewwb.core.KeyConcat;
import com.example.viewwb.core.TxRunner;
import com.example.viewwb.core.ValueCodec;
import com.example.viewwb.engine.ExecutionContext.AfterModel;
import com.example.viewwb.engine.FlowDefinition.Node;
import com.example.viewwb.exception.CustomException;
import com.example.viewwb.meta.MetaRepository;
import com.example.viewwb.meta.MetaSchema;
import com.example.viewwb.meta.ViewDefinition;
import com.example.viewwb.meta.ViewDefinition.ColumnDef;
import com.example.viewwb.meta.ViewDefinition.TableRef;
import com.example.viewwb.service.ReEventService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.io.DataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * View 行更新の実行エンジン。
 *
 * 責務分担(2026-07-18 設計合意):
 *  - エンジン入口: JSON 検証・型変換・差分検出・実行コンテキスト構築(モデル生成)
 *  - フロー(更新モジュール): モデル変数への読み書きと検証・分岐のみ
 *  - commit 到達時: dirty な After モデルを一括適用
 *      via=TX → 同一 Tx で backend put / via=RE → Before/After イベントを re_outbox へ INSERT
 *      + View 実体行の UPDATE(エンジン自動)
 *  全体が 1 つの ScalarDB Tx(読込ノードも同 Tx = Consensus Commit でレース安全)。
 */
@Service
public class UpdateEngine {

    private static final Logger log = LoggerFactory.getLogger(UpdateEngine.class);
    private static final int MAX_STEPS = 1000;

    private final MetaRepository meta;
    private final DynamicRepository repo;
    private final TxRunner tx;
    private final ReEventService reEvents;
    private final ObjectMapper mapper;

    public UpdateEngine(MetaRepository meta, DynamicRepository repo, TxRunner tx,
                        ReEventService reEvents, ObjectMapper mapper) {
        this.meta = meta;
        this.repo = repo;
        this.tx = tx;
        this.reEvents = reEvents;
        this.mapper = mapper;
    }

    /** モジュールが中断を選んだことを表す内部シグナル */
    static class FlowAborted extends RuntimeException {
        FlowAborted(String reason) {
            super(reason);
        }
    }

    public Map<String, Object> update(String viewName, Map<String, Object> rawRow) {
        ViewDefinition def = meta.viewDef(viewName);
        FlowDefinition flow = loadFlow(viewName);

        // 1. 入力の検証と型変換(view スキーマ)
        Map<String, Object> input = parseInput(def, rawRow);

        try {
            return tx.run("update view " + viewName, t -> {
                // 2. before = View キャッシュ現在行(実体 PK = <alias>_pk 複合。入力のキー列から導出)
                Map<String, Object> keyValues = viewEntityKey(def, input);
                Map<String, Object> before = repo.get(t, MetaSchema.NS_VIEWS, viewName, keyValues)
                        .orElseThrow(() -> new CustomException(
                                "View row not found for key " + keyValues, 404));

                // 3. 差分検出(未送信列は before 値で補完)
                for (ColumnDef c : def.columns()) {
                    if ((c.visible() || c.isKey()) && !input.containsKey(c.viewColumn())) {
                        input.put(c.viewColumn(), before.get(c.viewColumn()));
                    }
                }
                Set<String> changed = diff(def, input, before);
                if (changed.isEmpty()) {
                    return Map.of("result", "noop", "message", "変更はありません");
                }
                validateReColumns(def, changed, before);

                // 4. 実行コンテキスト構築(dirty テーブルの After モデル)
                ExecutionContext ctx = new ExecutionContext(def, input, before, changed);
                buildAfterModels(ctx);

                // 5. フロー実行(モジュール未定義なら素通し)
                if (flow != null) {
                    runFlow(flow, ctx, t);
                }

                // 6. 一括適用 + View 実体更新
                Map<String, Object> applied = apply(t, viewName, ctx, keyValues);
                applied.put("result", "committed");
                applied.put("changed", List.copyOf(changed));
                return applied;
            });
        } catch (FlowAborted e) {
            log.info("update of {} aborted by module: {}", viewName, e.getMessage());
            return Map.of("result", "aborted", "reason", e.getMessage());
        }
    }

    // ---- 入口: 検証・型変換・差分 -------------------------------------------

    private Map<String, Object> parseInput(ViewDefinition def, Map<String, Object> rawRow) {
        Map<String, Object> input = new LinkedHashMap<>();
        List<String> internalPkColumns = def.pkColumns();
        for (Map.Entry<String, Object> e : rawRow.entrySet()) {
            if (internalPkColumns.contains(e.getKey())) {
                continue; // 内部連結キーはクライアント値を信用せずサーバー側で再導出する
            }
            ColumnDef c = def.column(e.getKey()); // 未知列は 400
            input.put(c.viewColumn(),
                    ValueCodec.parse(c.viewColumn(), dataType(c), e.getValue()));
        }
        for (ColumnDef key : def.keyColumns()) {
            if (!input.containsKey(key.viewColumn())) {
                throw new CustomException("キー列 '" + key.viewColumn() + "' の値が必要です", 400);
            }
        }
        return input;
    }

    /** View 実体の PK(<alias>_pk 複合)を、入力のキー view 列から導出する */
    private Map<String, Object> viewEntityKey(ViewDefinition def, Map<String, Object> input) {
        Map<String, Object> key = new LinkedHashMap<>();
        for (TableRef table : def.tables()) {
            key.put(ViewDefinition.pkColumn(table.alias()), KeyConcat.encode(input,
                    def.keyColumnsOf(table.alias()).stream().map(ColumnDef::viewColumn).toList()));
        }
        return key;
    }

    private Set<String> diff(ViewDefinition def, Map<String, Object> input, Map<String, Object> before) {
        Set<String> changed = new LinkedHashSet<>();
        for (ColumnDef c : def.columns()) {
            if (!c.visible() && !c.isKey()) {
                continue;
            }
            Object now = input.get(c.viewColumn());
            Object old = before.get(c.viewColumn());
            if (Objects.equals(now, old)) {
                continue;
            }
            if (!c.updatable()) {
                throw new CustomException("Column '" + c.viewColumn() + "' is not updatable"
                        + " (value differs from current row)", 400);
            }
            changed.add(c.viewColumn());
        }
        return changed;
    }

    /**
     * RE 列の編集前提チェック(plan-009 承認往復)。
     * 承認待ちマーカーを view 実体へ書くため TEXT 列のみ / 承認待ち中の再編集は拒否。
     */
    private void validateReColumns(ViewDefinition def, Set<String> changed,
                                   Map<String, Object> before) {
        for (String vc : changed) {
            ColumnDef c = def.column(vc);
            if (!"RE".equalsIgnoreCase(c.via())) {
                continue;
            }
            if (!"TEXT".equalsIgnoreCase(c.kind())) {
                throw new CustomException("RE 列 '" + vc + "' は TEXT のみ編集できます"
                        + "(承認フローが UPDATE REQUESTED マーカーを書くため)", 400);
            }
            if (ReEventService.STATUS_REQUESTED.equals(before.get(vc))) {
                throw new CustomException("列 '" + vc + "' は承認待ち(UPDATE REQUESTED)のため"
                        + "編集できません。外部システム側の許可/却下をお待ちください", 400);
            }
        }
    }

    private void buildAfterModels(ExecutionContext ctx) {
        ViewDefinition def = ctx.def();
        Set<String> dirtyAliases = new LinkedHashSet<>();
        ctx.changedViewColumns().forEach(vc -> dirtyAliases.add(def.column(vc).sourceAlias()));

        for (String alias : dirtyAliases) {
            TableRef table = def.table(alias);
            AfterModel model = new AfterModel(table);
            // キー束縛: この alias のキー view 列(before 値 — キーは不変)
            for (ColumnDef c : def.columns()) {
                if (c.isKey() && c.sourceAlias().equals(alias)) {
                    model.bindKey(c.sourceColumn(), ctx.before().get(c.viewColumn()));
                }
            }
            // updatable 列の初期値 = 入力値(changed フラグ付き)
            for (ColumnDef c : def.columns()) {
                if (c.updatable() && c.sourceAlias().equals(alias)) {
                    model.initValue(c.sourceColumn(), ctx.input().get(c.viewColumn()),
                            ctx.changedViewColumns().contains(c.viewColumn()));
                }
                if (!c.isKey() && c.sourceAlias().equals(alias)) {
                    model.allowWrite(c.sourceColumn());
                }
            }
            ctx.putAfterModel(alias, model);
        }
    }

    // ---- フロー実行 ---------------------------------------------------------

    private FlowDefinition loadFlow(String viewName) {
        Optional<String> json = meta.findModuleJson(viewName);
        if (json.isEmpty()) {
            return null;
        }
        try {
            return mapper.readValue(json.get(), FlowDefinition.class);
        } catch (JsonProcessingException e) {
            throw new CustomException("Broken update module JSON: " + e.getOriginalMessage(), e, 500);
        }
    }

    private void runFlow(FlowDefinition flow, ExecutionContext ctx, DistributedTransaction t)
            throws Exception {
        Node node = flow.startNode();
        int steps = 0;
        while (true) {
            if (++steps > MAX_STEPS) {
                throw new CustomException("Flow exceeded " + MAX_STEPS + " steps (cycle?)", 400);
            }
            String route = "next";
            switch (node.type()) {
                case "start" -> { /* Tx begin + 入力受信(エンジンが実施済み) */ }
                case "read" -> execRead(node, ctx, t);
                case "var" -> ctx.putVariable(node.requiredParam("name"),
                        evalExpr(node.requiredParam("expr"), ctx));
                case "compare" -> ctx.setLastCompare(evalCompare(node, ctx));
                case "branch" -> route = ctx.lastCompare() ? "true" : "false";
                case "update" -> execUpdate(node, ctx);
                case "abort" -> throw new FlowAborted(
                        Optional.ofNullable(node.param("reason")).orElse("aborted"));
                case "commit" -> {
                    return;
                }
                default -> throw new CustomException("Unknown flow node type: " + node.type(), 400);
            }
            List<Integer> nextIds = node.next() == null ? null : node.next().get(route);
            if (nextIds == null || nextIds.isEmpty()) {
                throw new CustomException("Flow node " + node.id() + " (" + node.type()
                        + ") has no outgoing '" + route + "' connection — commit/abort に到達しません", 400);
            }
            node = flow.node(nextIds.get(0));
        }
    }

    /** read: ScalarDB 配下の任意テーブルを同一 Tx で読む(View 非参加テーブルも可) */
    private void execRead(Node node, ExecutionContext ctx, DistributedTransaction t) throws Exception {
        String tableRef = node.requiredParam("table");
        String[] nsTable = resolveTable(tableRef, ctx);
        Map<String, Object> keyValues = parseKeyValues(node.requiredParam("key"), ctx);
        Map<String, Object> record = repo.get(t, nsTable[0], nsTable[1], keyValues)
                .orElseThrow(() -> new FlowAborted("レコードが見つかりません: "
                        + nsTable[0] + "." + nsTable[1] + " key=" + keyValues));
        ctx.putVariable(node.requiredParam("into"), record);
    }

    private Map<String, Object> parseKeyValues(String keyParam, ExecutionContext ctx) {
        Map<String, Object> keyValues = new LinkedHashMap<>();
        for (String pair : keyParam.split(",")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                throw new CustomException("key must be col=expr[, ...]: " + pair, 400);
            }
            keyValues.put(pair.substring(0, eq).trim(), evalExpr(pair.substring(eq + 1), ctx));
        }
        return keyValues;
    }

    /**
     * update: 対象 alias の After モデルに最終値を確定(put はしない — commit 時に一括適用)。
     * key 指定あり(plan-007)は AfterModel を経由しない直接更新として蓄積する。
     * 同一テーブルの別の行(AfterModel はキー = before 値固定のため書けない)や
     * View 非参加テーブルを対象にできる。View 実体行への伝播は行わない。
     */
    private void execUpdate(Node node, ExecutionContext ctx) {
        String tableRef = node.requiredParam("table");
        String keyParam = node.param("key");
        if (keyParam != null && !keyParam.isBlank()) {
            String[] nsTable = resolveTable(tableRef, ctx);
            Map<String, Object> changes = new LinkedHashMap<>();
            for (String assignment : node.requiredParam("set").split(",")) {
                int eq = assignment.indexOf('=');
                if (eq < 0) {
                    throw new CustomException("update set must be col = expr[, ...]: " + assignment, 400);
                }
                changes.put(assignment.substring(0, eq).trim(),
                        evalExpr(assignment.substring(eq + 1), ctx));
            }
            ctx.addKeyedUpdate(new ExecutionContext.KeyedUpdate(
                    nsTable[0], nsTable[1], parseKeyValues(keyParam, ctx), changes));
            return;
        }
        String alias = aliasFor(tableRef, ctx);
        AfterModel model = ctx.afterModel(alias);
        if (model == null) {
            // 変更が無かったテーブルへのフロー起点の書き込み: オンデマンド生成
            buildOnDemandModel(ctx, alias);
            model = ctx.afterModel(alias);
        }
        for (String assignment : node.requiredParam("set").split(",")) {
            int eq = assignment.indexOf('=');
            if (eq < 0) {
                throw new CustomException("update set must be col = expr[, ...]: " + assignment, 400);
            }
            String column = assignment.substring(0, eq).trim();
            Object value = evalExpr(assignment.substring(eq + 1), ctx);
            model.set(column, value, true);
        }
    }

    private void buildOnDemandModel(ExecutionContext ctx, String alias) {
        ViewDefinition def = ctx.def();
        TableRef table = def.table(alias);
        AfterModel model = new AfterModel(table);
        for (ColumnDef c : def.columns()) {
            if (c.isKey() && c.sourceAlias().equals(alias)) {
                model.bindKey(c.sourceColumn(), ctx.before().get(c.viewColumn()));
            }
        }
        for (ColumnDef c : def.columns()) {
            if (c.updatable() && c.sourceAlias().equals(alias)) {
                model.initValue(c.sourceColumn(), ctx.input().get(c.viewColumn()), false);
            }
            if (!c.isKey() && c.sourceAlias().equals(alias)) {
                model.allowWrite(c.sourceColumn());
            }
        }
        ctx.putAfterModel(alias, model);
    }

    /** "ns.table" か alias を [namespace, table] に解決 */
    private String[] resolveTable(String ref, ExecutionContext ctx) {
        String trimmed = ref.trim();
        if (trimmed.contains(".")) {
            String[] parts = trimmed.split("\\.", 2);
            return new String[]{parts[0], parts[1]};
        }
        TableRef table = ctx.def().table(trimmed);
        return new String[]{table.namespace(), table.table()};
    }

    private String aliasFor(String ref, ExecutionContext ctx) {
        String trimmed = ref.trim();
        if (!trimmed.contains(".")) {
            return trimmed;
        }
        String[] parts = trimmed.split("\\.", 2);
        return ctx.def().tables().stream()
                .filter(tr -> tr.namespace().equals(parts[0]) && tr.table().equals(parts[1]))
                .findFirst()
                .map(TableRef::alias)
                .orElseThrow(() -> new CustomException(
                        "update target is not a view table: " + ref, 400));
    }

    // ---- 式評価(M1: INT 中心の最小言語) ------------------------------------

    /** operand | operand (+|-|*|/) operand。operand = 整数リテラル or 参照 */
    Object evalExpr(String expr, ExecutionContext ctx) {
        String trimmed = expr.trim();
        var matcher = java.util.regex.Pattern
                .compile("^(.+?)\\s*([+\\-*/])\\s*([^+\\-*/]+)$")
                .matcher(trimmed);
        if (matcher.matches() && !trimmed.startsWith("-")) {
            long left = asLong(ctx.resolve(matcher.group(1)), matcher.group(1));
            long right = asLong(ctx.resolve(matcher.group(3)), matcher.group(3));
            return switch (matcher.group(2)) {
                case "+" -> left + right;
                case "-" -> left - right;
                case "*" -> left * right;
                default -> {
                    if (right == 0) {
                        throw new FlowAborted("0 除算: " + trimmed);
                    }
                    yield left / right;
                }
            };
        }
        return ctx.resolve(trimmed);
    }

    private boolean evalCompare(Node node, ExecutionContext ctx) {
        Object left = evalExpr(node.requiredParam("left"), ctx);
        Object right = evalExpr(node.requiredParam("right"), ctx);
        String op = node.requiredParam("op");
        if ("==".equals(op) || "!=".equals(op)) {
            boolean eq = Objects.equals(normalize(left), normalize(right));
            return "==".equals(op) ? eq : !eq;
        }
        long l = asLong(left, node.param("left"));
        long r = asLong(right, node.param("right"));
        return switch (op) {
            case ">=" -> l >= r;
            case ">" -> l > r;
            case "<=" -> l <= r;
            case "<" -> l < r;
            default -> throw new CustomException("Unknown compare op: " + op, 400);
        };
    }

    private Object normalize(Object value) {
        return value instanceof Number n ? n.longValue() : value;
    }

    private long asLong(Object value, String context) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new CustomException("'" + context + "' is not a number (INT 比較のみ対応): "
                + value, 400);
    }

    // ---- commit 時の一括適用 ------------------------------------------------

    private Map<String, Object> apply(DistributedTransaction t, String viewName,
                                      ExecutionContext ctx, Map<String, Object> viewKey)
            throws Exception {
        ViewDefinition def = ctx.def();
        int txWrites = 0;
        int reEventCount = 0;
        int viewRowsUpdated = 0;

        for (AfterModel model : ctx.afterModels().values()) {
            if (model.touched().isEmpty()) {
                continue;
            }
            Map<String, Object> writeValues = new LinkedHashMap<>();
            model.touched().forEach(col -> writeValues.put(col, model.values().get(col)));

            if (model.table().isScalarDb()) {
                repo.update(t, model.table().namespace(), model.table().table(),
                        model.keyValues(), writeValues);
                txWrites++;
            } else {
                // 非 ScalarDB 宛先(plan-009 承認往復): 外部生テーブルはここでは変更せず
                // リクエストイベントのみ送る。view 実体には承認待ちマーカーを書き、
                // 復路(ViewWritebackResolved)の SUCCEEDED / REJECTED で確定する。
                Map<String, Object> beforeImage = new LinkedHashMap<>();
                for (ColumnDef c : def.columns()) {
                    if (c.sourceAlias().equals(model.table().alias())) {
                        beforeImage.put(c.sourceColumn(), ctx.before().get(c.viewColumn()));
                    }
                }
                Map<String, Object> requestedChanges = new LinkedHashMap<>();
                model.touched().forEach(col -> requestedChanges.put(col, model.values().get(col)));
                reEvents.enqueueChangeRequest(t, viewName, viewKey,
                        model.table().namespace(), model.table().table(),
                        model.keyValues(), requestedChanges, beforeImage);
                reEventCount++;
                // 伝播が view 実体へ書く値を承認待ちマーカーへ差し替える
                model.touched().forEach(col ->
                        model.values().put(col, ReEventService.STATUS_REQUESTED));
            }

        }

        // keyed update(plan-007): 同一 Tx で直接更新(列存在チェックは repo.update 内)。
        // 対象は View 非参加テーブル想定のため実体行への伝播はしない。
        for (ExecutionContext.KeyedUpdate ku : ctx.keyedUpdates()) {
            repo.update(t, ku.namespace(), ku.table(), ku.keyValues(), ku.changes());
            txWrites++;
        }

        // View 実体への伝播(plan-005): 同じソースレコードを写している view 全行を
        // <alias>_pk で逆引きし、alias 由来の変更列を同一 Tx で更新する。
        // ScalarDB は同一 Tx 内で書込済みデータへの scan を禁止する(DB-CORE-10106)ため、
        // 全 alias の逆引き scan を先に済ませてから、まとめて update する2段構え。
        List<Propagation> propagations = new ArrayList<>();
        for (AfterModel model : ctx.afterModels().values()) {
            if (!model.touched().isEmpty()) {
                collectPropagation(t, viewName, def, model, viewKey, propagations);
            }
        }
        for (Propagation p : propagations) {
            for (Map<String, Object> hitKey : p.hitKeys()) {
                repo.update(t, MetaSchema.NS_VIEWS, viewName, hitKey, p.changes());
                viewRowsUpdated++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("txWrites", txWrites);
        summary.put("reEvents", reEventCount);
        summary.put("viewRowsUpdated", viewRowsUpdated);
        return summary;
    }

    /** 1 alias 分の伝播内容: 反映する view 列変更と、対象行の実体 PK 群 */
    private record Propagation(Map<String, Object> changes, List<Map<String, Object>> hitKeys) {
    }

    /**
     * touched な alias の変更列と、同じ <alias>_pk を持つ view 全行の PK を収集する。
     * 逆引きは、パーティションキー側 alias = パーティション scan /
     * クラスタリング側 alias = SecondaryIndex 等価スキャン(design-note 決定欄)。
     */
    private void collectPropagation(DistributedTransaction t, String viewName, ViewDefinition def,
                                    AfterModel model, Map<String, Object> viewKey,
                                    List<Propagation> propagations) throws Exception {
        String alias = model.table().alias();
        Map<String, Object> aliasChanges = new LinkedHashMap<>();
        for (ColumnDef c : def.columns()) {
            if (c.sourceAlias().equals(alias) && model.touched().contains(c.sourceColumn())) {
                aliasChanges.put(c.viewColumn(), model.values().get(c.sourceColumn()));
            }
        }
        if (aliasChanges.isEmpty()) {
            return;
        }
        String pkColumn = ViewDefinition.pkColumn(alias);
        Object pkValue = viewKey.get(pkColumn);
        boolean isPartitionAlias = def.tables().get(0).alias().equals(alias);
        List<Map<String, Object>> hits = isPartitionAlias
                ? repo.scanPartition(t, MetaSchema.NS_VIEWS, viewName, Map.of(pkColumn, pkValue))
                : repo.scanByIndex(t, MetaSchema.NS_VIEWS, viewName, pkColumn, pkValue);
        List<Map<String, Object>> hitKeys = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            Map<String, Object> hitKey = new LinkedHashMap<>();
            def.pkColumns().forEach(pk -> hitKey.put(pk, hit.get(pk)));
            hitKeys.add(hitKey);
        }
        propagations.add(new Propagation(aliasChanges, hitKeys));
    }

    private DataType dataType(ColumnDef c) {
        try {
            return DataType.valueOf(c.kind().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CustomException("Unknown column kind '" + c.kind() + "'", 400);
        }
    }
}
