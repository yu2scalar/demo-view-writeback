package com.example.viewwb.service;

import com.example.viewwb.core.DynamicRepository;
import com.example.viewwb.core.TxRunner;
import com.example.viewwb.exception.CustomException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 外部システムの模擬(plan-009)。RE が配送した変更リクエスト
 * (<ns>.re_inbox の ViewWritebackChangeRequested)を人が確認し、
 * 許可 = 自システムの生テーブルを直接 JDBC で更新 + SUCCEEDED を返送 /
 * 却下 = REJECTED を返送する。返送は <ns>.re_outbox への transactional outbox
 * (Cluster 経由 ScalarDB Tx)で、inbox の status 更新と同一 Tx。
 *
 * 生テーブルの直接 JDBC 更新と返送 Tx は非原子(模擬のための割り切り。
 * 実システムでは外部側の Tx/outbox で担保する想定 — plan-009 §5)。
 */
@Service
public class ExternalSystemService {

    private static final Logger log = LoggerFactory.getLogger(ExternalSystemService.class);
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");
    private static final String T_RE_INBOX = "re_inbox";
    /** inbox status: 0/null=未処理, 1=許可済み, 2=却下済み */
    public static final int STATUS_APPROVED = 1;
    public static final int STATUS_REJECTED = 2;

    private final DynamicRepository repo;
    private final TxRunner tx;
    private final ReEventService reEvents;
    private final ObjectMapper mapper;
    private final String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;

    public ExternalSystemService(DynamicRepository repo, TxRunner tx, ReEventService reEvents,
                                 ObjectMapper mapper,
                                 @Value("${app.external.jdbc-url}") String jdbcUrl,
                                 @Value("${app.external.username}") String jdbcUser,
                                 @Value("${app.external.password}") String jdbcPassword) {
        this.repo = repo;
        this.tx = tx;
        this.reEvents = reEvents;
        this.mapper = mapper;
        this.jdbcUrl = jdbcUrl;
        this.jdbcUser = jdbcUser;
        this.jdbcPassword = jdbcPassword;
    }

    /** inbox 一覧(変更リクエストのみ、payload はパース済みで返す) */
    public List<Map<String, Object>> listInbox(String namespace) {
        requireInboxNamespace(namespace);
        List<Map<String, Object>> rows = tx.run("scan " + namespace + ".re_inbox",
                t -> repo.scanAll(t, namespace, T_RE_INBOX));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!ReEventService.EVENT_TYPE.equals(row.get("event_type"))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eventId", row.get("event_id"));
            item.put("deliveredAt", row.get("delivered_at"));
            item.put("status", row.get("status"));
            item.put("payload", parsePayload((String) row.get("body")));
            result.add(item);
        }
        result.sort((a, b) -> String.valueOf(a.get("eventId"))
                .compareTo(String.valueOf(b.get("eventId"))));
        return result;
    }

    /** 許可: 生テーブルを直接 JDBC で更新 + SUCCEEDED 返送 + status=1 */
    public Map<String, Object> approve(String namespace, String eventId) {
        Map<String, Object> row = findPendingRequest(namespace, eventId);
        Map<String, Object> payload = parsePayload((String) row.get("body"));
        int updated = applyToRawTable(payload);
        resolve(namespace, row, payload, ReEventService.RESULT_SUCCEEDED, STATUS_APPROVED);
        log.info("external approve: ns={} event={} rawRowsUpdated={}", namespace, eventId, updated);
        return Map.of("result", "approved", "rawRowsUpdated", updated);
    }

    /** 却下: REJECTED 返送 + status=2(生テーブルは触らない) */
    public Map<String, Object> reject(String namespace, String eventId) {
        Map<String, Object> row = findPendingRequest(namespace, eventId);
        Map<String, Object> payload = parsePayload((String) row.get("body"));
        resolve(namespace, row, payload, ReEventService.RESULT_REJECTED, STATUS_REJECTED);
        log.info("external reject: ns={} event={}", namespace, eventId);
        return Map.of("result", "rejected");
    }

    // ---- 内部 ----------------------------------------------------------------

    private void requireInboxNamespace(String namespace) {
        if (!IDENTIFIER.matcher(namespace).matches()
                || repo.metadataOrNull(namespace, T_RE_INBOX) == null) {
            throw new CustomException("inbox が存在しません: " + namespace, 404);
        }
    }

    private Map<String, Object> findPendingRequest(String namespace, String eventId) {
        requireInboxNamespace(namespace);
        List<Map<String, Object>> rows = tx.run("scan " + namespace + ".re_inbox",
                t -> repo.scanAll(t, namespace, T_RE_INBOX));
        Map<String, Object> found = rows.stream()
                .filter(r -> ReEventService.EVENT_TYPE.equals(r.get("event_type"))
                        && eventId.equals(r.get("event_id")))
                .findFirst()
                .orElseThrow(() -> new CustomException("リクエストが見つかりません: " + eventId, 404));
        Object status = found.get("status");
        if (status instanceof Number n && n.intValue() != 0) {
            throw new CustomException("既に処理済みのリクエストです: " + eventId, 409);
        }
        return found;
    }

    /** 返送イベント + inbox status 更新を同一 Cluster Tx で行う(exactly-once) */
    private void resolve(String namespace, Map<String, Object> inboxRow,
                         Map<String, Object> payload, String result, int newStatus) {
        Map<String, Object> keys = new LinkedHashMap<>();
        for (String k : List.of("event_type", "partition", "event_id", "step_id", "seq")) {
            keys.put(k, inboxRow.get(k));
        }
        tx.run("resolve " + namespace + " " + inboxRow.get("event_id"), t -> {
            reEvents.enqueueResolution(t, namespace, result, payload);
            repo.update(t, namespace, T_RE_INBOX, keys, Map.of("status", newStatus));
            return null;
        });
    }

    /** payload の dest_table / dest_key / requested_changes で生テーブルを UPDATE */
    private int applyToRawTable(Map<String, Object> payload) {
        String destTable = (String) payload.get("dest_table");
        Map<String, Object> destKey = asMap(payload.get("dest_key"));
        Map<String, Object> changes = asMap(payload.get("requested_changes"));
        if (destTable == null || destKey == null || destKey.isEmpty()
                || changes == null || changes.isEmpty()) {
            throw new CustomException("payload に dest_table / dest_key / requested_changes が"
                    + "揃っていません(旧形式のイベントは許可できません)", 400);
        }
        String[] parts = destTable.split("\\.", 2);
        if (parts.length != 2 || !IDENTIFIER.matcher(parts[0]).matches()
                || !IDENTIFIER.matcher(parts[1]).matches()) {
            throw new CustomException("不正な dest_table: " + destTable, 400);
        }
        for (String col : changes.keySet()) {
            requireIdentifier(col);
        }
        for (String col : destKey.keySet()) {
            requireIdentifier(col);
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(parts[0]).append('.').append(parts[1])
                .append(" SET ");
        List<Object> params = new ArrayList<>();
        int i = 0;
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            if (i++ > 0) {
                sql.append(", ");
            }
            sql.append(e.getKey()).append(" = ?");
            params.add(e.getValue());
        }
        sql.append(" WHERE ");
        i = 0;
        for (Map.Entry<String, Object> e : destKey.entrySet()) {
            if (i++ > 0) {
                sql.append(" AND ");
            }
            sql.append(e.getKey()).append(" = ?");
            params.add(e.getValue());
        }
        try (Connection conn = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int p = 0; p < params.size(); p++) {
                ps.setObject(p + 1, params.get(p));
            }
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new CustomException("生テーブルの更新に失敗しました: " + e.getMessage(), e, 500);
        }
    }

    private void requireIdentifier(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new CustomException("不正な列名: " + name, 400);
        }
    }

    private Map<String, Object> parsePayload(String body) {
        try {
            JsonNode payload = mapper.readTree(body == null ? "{}" : body).path("payload");
            return payload.isMissingNode() ? Map.of()
                    : mapper.convertValue(payload, new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
        } catch (Exception e) {
            log.warn("inbox body parse failed: {}", e.getMessage());
            return Map.of("_raw", body);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
