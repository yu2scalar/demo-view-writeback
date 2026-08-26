package com.example.viewwb.service;

import com.example.viewwb.core.DynamicRepository;
import com.example.viewwb.core.TxRunner;
import com.example.viewwb.meta.MetaRepository;
import com.example.viewwb.meta.MetaSchema;
import com.example.viewwb.meta.ViewDefinition;
import com.example.viewwb.meta.ViewDefinition.ColumnDef;
import com.example.viewwb.meta.ViewDefinition.TableRef;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * view システム側の inbox コンシューマ(plan-009)。外部システムが返した
 * ViewWritebackResolved(viewmgr.re_inbox に RE が配送)を自動ポーリングで処理し、
 * 承認待ち(UPDATE REQUESTED)の view 実体行を確定する:
 * SUCCEEDED → requested_changes(After)/ REJECTED → before。
 * 同一ソース行を写す全 view 行へ pk 逆引きで反映し、inbox の status 更新と
 * 同一 Cluster Tx で行う(exactly-once)。
 */
@Service
public class InboxApplyService {

    private static final Logger log = LoggerFactory.getLogger(InboxApplyService.class);
    private static final String T_RE_INBOX = "re_inbox";
    /** inbox status: 0/null=未処理, 1=適用済み, 9=適用不能(スキップ) */
    private static final int STATUS_APPLIED = 1;
    private static final int STATUS_SKIPPED = 9;

    private final MetaRepository meta;
    private final DynamicRepository repo;
    private final TxRunner tx;
    private final ObjectMapper mapper;

    public InboxApplyService(MetaRepository meta, DynamicRepository repo, TxRunner tx,
                             ObjectMapper mapper) {
        this.meta = meta;
        this.repo = repo;
        this.tx = tx;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        List<Map<String, Object>> pending;
        try {
            pending = tx.run("scan viewmgr.re_inbox", t ->
                    repo.scanAll(t, MetaSchema.NS_META, T_RE_INBOX)).stream()
                    .filter(r -> r.get("event_type") instanceof String et
                            && et.startsWith(ReEventService.EVENT_TYPE_RESOLVED))
                    .filter(r -> !(r.get("status") instanceof Number n) || n.intValue() == 0)
                    .toList();
        } catch (Exception e) {
            log.warn("viewmgr inbox scan failed (次回リトライ): {}", e.getMessage());
            return;
        }
        for (Map<String, Object> row : pending) {
            try {
                applyOne(row);
            } catch (Exception e) {
                log.warn("resolution {} の適用に失敗 — スキップ扱いにします: {}",
                        row.get("event_id"), e.getMessage());
                markStatus(row, STATUS_SKIPPED);
            }
        }
    }

    private void applyOne(Map<String, Object> inboxRow) throws Exception {
        var root = mapper.readTree((String) inboxRow.get("body"));
        Map<String, Object> payload = root.path("payload").isMissingNode()
                ? Map.of()
                : mapper.convertValue(root.get("payload"),
                        new TypeReference<Map<String, Object>>() {
                        });
        String result = (String) payload.get("result");
        String source = (String) payload.get("source");
        String destTable = (String) payload.get("dest_table");
        Map<String, Object> viewKey = asMap(payload.get("view_key"));
        Map<String, Object> requested = asMap(payload.get("requested_changes"));
        Map<String, Object> before = asMap(payload.get("before"));
        if (result == null || source == null || !source.startsWith("views.")
                || destTable == null || viewKey == null || requested == null || before == null) {
            throw new IllegalStateException("payload が承認往復の形式ではありません");
        }
        String viewName = source.substring("views.".length());
        ViewDefinition def = meta.viewDef(viewName); // view が消えていれば 404 → スキップ
        TableRef table = def.tables().stream()
                .filter(tr -> destTable.equals(tr.namespace() + "." + tr.table()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "dest_table が view に参加していません: " + destTable));

        // 確定値: SUCCEEDED → After(requested)、REJECTED → Before(requested の列のみ)
        boolean succeeded = ReEventService.RESULT_SUCCEEDED.equals(result);
        Map<String, Object> viewChanges = new LinkedHashMap<>();
        for (String sourceColumn : requested.keySet()) {
            Object value = succeeded ? requested.get(sourceColumn) : before.get(sourceColumn);
            for (ColumnDef c : def.columns()) {
                if (c.sourceAlias().equals(table.alias()) && c.sourceColumn().equals(sourceColumn)) {
                    viewChanges.put(c.viewColumn(), value);
                }
            }
        }
        if (viewChanges.isEmpty()) {
            throw new IllegalStateException("requested_changes が view 列に対応しません");
        }

        String pkColumn = ViewDefinition.pkColumn(table.alias());
        Object pkValue = viewKey.get(pkColumn);
        boolean isPartitionAlias = def.tables().get(0).alias().equals(table.alias());

        tx.run("apply resolution " + inboxRow.get("event_id"), t -> {
            // scan を先に済ませてから書く(DB-CORE-10106)
            List<Map<String, Object>> hits = isPartitionAlias
                    ? repo.scanPartition(t, MetaSchema.NS_VIEWS, viewName, Map.of(pkColumn, pkValue))
                    : repo.scanByIndex(t, MetaSchema.NS_VIEWS, viewName, pkColumn, pkValue);
            List<Map<String, Object>> hitKeys = new ArrayList<>();
            for (Map<String, Object> hit : hits) {
                Map<String, Object> hitKey = new LinkedHashMap<>();
                def.pkColumns().forEach(pk -> hitKey.put(pk, hit.get(pk)));
                hitKeys.add(hitKey);
            }
            for (Map<String, Object> hitKey : hitKeys) {
                repo.update(t, MetaSchema.NS_VIEWS, viewName, hitKey, viewChanges);
            }
            repo.update(t, MetaSchema.NS_META, T_RE_INBOX, inboxKeys(inboxRow),
                    Map.of("status", STATUS_APPLIED));
            return null;
        });
        log.info("resolution applied: view={} result={} event={}",
                viewName, result, inboxRow.get("event_id"));
    }

    private void markStatus(Map<String, Object> inboxRow, int status) {
        try {
            tx.run("mark inbox " + inboxRow.get("event_id"), t -> {
                repo.update(t, MetaSchema.NS_META, T_RE_INBOX, inboxKeys(inboxRow),
                        Map.of("status", status));
                return null;
            });
        } catch (Exception e) {
            log.warn("inbox status 更新に失敗(次回再試行): {}", e.getMessage());
        }
    }

    private Map<String, Object> inboxKeys(Map<String, Object> row) {
        Map<String, Object> keys = new LinkedHashMap<>();
        for (String k : List.of("event_type", "partition", "event_id", "step_id", "seq")) {
            keys.put(k, row.get(k));
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
