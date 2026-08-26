package com.example.viewwb.service;

import com.example.viewwb.exception.CustomException;
import com.example.viewwb.util.UuidV7;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.Insert;
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.io.Key;
import org.example.re.sdk.builder.ReEventBodyBuilder;
import org.example.re.sdk.model.RoutingDestination;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.example.viewwb.meta.MetaSchema.NS_META;
import static com.example.viewwb.meta.MetaSchema.T_RE_OUTBOX;

/**
 * Emits write-back change requests for externally-owned columns (via=RE) as
 * ScalarRE events: an INSERT into viewmgr.re_outbox inside the caller's
 * transaction (transactional outbox). The view update and the event either
 * both commit or both roll back. ScalarRE polls the outbox and delivers to
 * the destination namespace's re_inbox exactly once, one-way (atomic
 * delivery, ack not required).
 *
 * plan-009: the flow is now an approval round-trip. The request payload
 * carries everything the external system needs to apply the change to its
 * own table (dest_table / dest_key / requested_changes), and the external
 * side answers with a {@link #EVENT_TYPE_RESOLVED} event (SUCCEEDED /
 * REJECTED) routed back to the viewmgr namespace, which the in-app consumer
 * applies to the view entity (After or Before).
 */
@Service
public class ReEventService {

    public static final String EVENT_TYPE = "ViewWritebackChangeRequested";
    /**
     * 復路 event-type の接頭辞。実際の event-type は「接頭辞_送信元namespace」
     * (例 ViewWritebackResolved_payroll)。ScalarRE 0.9.1 は event-type 名を
     * 名前キーで管理するため、同名を複数 namespace に定義すると 2 つ目以降が
     * paused のまま処理されない(2026-07-20 実測)— namespace ごとに一意にする。
     */
    public static final String EVENT_TYPE_RESOLVED = "ViewWritebackResolved";

    public static String resolvedEventType(String namespace) {
        return EVENT_TYPE_RESOLVED + "_" + namespace;
    }
    public static final String RESULT_SUCCEEDED = "SUCCEEDED";
    public static final String RESULT_REJECTED = "REJECTED";
    /** 承認待ちの間、view 実体の RE 列に入る保留マーカー(TEXT 列のみ対応) */
    public static final String STATUS_REQUESTED = "UPDATE REQUESTED";
    /** 復路イベントの宛先(view システム側の namespace) */
    public static final String VIEW_SYSTEM_NAMESPACE = NS_META;

    public void enqueueChangeRequest(DistributedTransaction tx, String viewName,
                                     Map<String, Object> viewKey, String destNamespace,
                                     String destTable, Map<String, Object> destKey,
                                     Map<String, Object> requestedChanges,
                                     Map<String, Object> before) throws TransactionException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "views." + viewName);
        payload.put("view_key", viewKey);
        // 外部側が生テーブルを更新するための情報(plan-009 で追加)
        payload.put("dest_table", destNamespace + "." + destTable);
        payload.put("dest_key", destKey);
        payload.put("requested_changes", requestedChanges);
        payload.put("before", before);
        insertOutboxEvent(tx, NS_META, EVENT_TYPE, buildBody(destNamespace, payload));
    }

    /**
     * 外部システム(の模擬)が承認結果を view システムへ返す復路イベント。
     * 元イベントの payload をエコーし result(SUCCEEDED / REJECTED)を付ける。
     * 外部側 namespace の re_outbox へ INSERT する(呼び出し側の Tx 内)。
     */
    public void enqueueResolution(DistributedTransaction tx, String externalNamespace,
                                  String result, Map<String, Object> originalPayload)
            throws TransactionException {
        Map<String, Object> payload = new LinkedHashMap<>(originalPayload);
        payload.put("result", result);
        insertOutboxEvent(tx, externalNamespace, resolvedEventType(externalNamespace),
                buildBody(VIEW_SYSTEM_NAMESPACE, payload));
    }

    private String buildBody(String destNamespace, Map<String, Object> payload) {
        try {
            return ReEventBodyBuilder.create()
                    .deliveryType("atomic")
                    .addStep(step -> step
                            .stepId(1)
                            .addSequence(seq -> seq
                                    .seq(1)
                                    .routing(new RoutingDestination(destNamespace, false))
                                    .payload(payload)))
                    .toJson();
        } catch (JsonProcessingException e) {
            throw new CustomException("Failed to build RE event body: " + e.getMessage(), e, 500);
        }
    }

    private void insertOutboxEvent(DistributedTransaction tx, String namespace,
                                   String eventType, String body) throws TransactionException {
        // event_id MUST be UUIDv7 — RE's poller derives its scan range from time.
        tx.insert(Insert.newBuilder()
                .namespace(namespace).table(T_RE_OUTBOX)
                .partitionKey(Key.ofText("event_type", eventType))
                .clusteringKey(Key.ofText("event_id", UuidV7.generate()))
                .textValue("body", body)
                .bigIntValue("created_at", System.currentTimeMillis())
                .build());
    }
}
