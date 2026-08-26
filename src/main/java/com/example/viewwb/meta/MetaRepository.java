package com.example.viewwb.meta;

import com.example.viewwb.core.DynamicRepository;
import com.example.viewwb.core.TxRunner;
import com.example.viewwb.exception.CustomException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.exception.transaction.TransactionException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.example.viewwb.meta.MetaSchema.NS_META;
import static com.example.viewwb.meta.MetaSchema.T_UPDATE_MODULE;
import static com.example.viewwb.meta.MetaSchema.T_VIEW_DEF;

/**
 * view_def / update_module の CRUD。definition_json / flow_json は GUI の出力を
 * そのまま保存し、読み出し時に型付きモデルへ復元する。
 */
@Repository
public class MetaRepository {

    private final DynamicRepository repo;
    private final TxRunner tx;
    private final ObjectMapper mapper;

    public MetaRepository(DynamicRepository repo, TxRunner tx, ObjectMapper mapper) {
        this.repo = repo;
        this.tx = tx;
        this.mapper = mapper;
    }

    // ---- view_def ----------------------------------------------------------

    public void insertViewDef(DistributedTransaction t, ViewDefinition def, String sql)
            throws TransactionException {
        repo.insert(t, NS_META, T_VIEW_DEF, Map.of(
                "view_name", def.viewName(),
                "definition_json", toJson(def),
                "sql_text", sql,
                "status", "ACTIVE",
                "created_at", System.currentTimeMillis(),
                "refreshed_at", 0L));
    }

    /** 既存 View の定義を置換(created_at は維持) */
    public void updateViewDef(DistributedTransaction t, ViewDefinition def, String sql)
            throws TransactionException {
        repo.update(t, NS_META, T_VIEW_DEF, Map.of("view_name", def.viewName()), Map.of(
                "definition_json", toJson(def),
                "sql_text", sql,
                "status", "ACTIVE",
                "refreshed_at", 0L));
    }

    public void touchRefreshedAt(DistributedTransaction t, String viewName)
            throws TransactionException {
        repo.update(t, NS_META, T_VIEW_DEF, Map.of("view_name", viewName),
                Map.of("refreshed_at", System.currentTimeMillis()));
    }

    public Optional<Map<String, Object>> findViewDefRow(DistributedTransaction t, String viewName)
            throws TransactionException {
        return repo.get(t, NS_META, T_VIEW_DEF, Map.of("view_name", viewName));
    }

    public Map<String, Object> viewDefRow(String viewName) {
        return tx.run("get view_def", t -> findViewDefRow(t, viewName))
                .orElseThrow(() -> new CustomException("View が見つかりません: " + viewName, 404));
    }

    public ViewDefinition viewDef(String viewName) {
        return parseDefinition((String) viewDefRow(viewName).get("definition_json"));
    }

    public List<Map<String, Object>> listViewDefRows() {
        return tx.run("list view_def", t -> repo.scanAll(t, NS_META, T_VIEW_DEF));
    }

    public void deleteViewDef(DistributedTransaction t, String viewName) throws TransactionException {
        repo.delete(t, NS_META, T_VIEW_DEF, Map.of("view_name", viewName));
        repo.delete(t, NS_META, T_UPDATE_MODULE, Map.of("view_name", viewName));
    }

    public ViewDefinition parseDefinition(String json) {
        try {
            ViewDefinition def = mapper.readValue(json, ViewDefinition.class);
            def.validate();
            return def;
        } catch (JsonProcessingException e) {
            throw new CustomException("Broken view definition JSON: " + e.getOriginalMessage(), e, 500);
        }
    }

    // ---- update_module -----------------------------------------------------

    public void saveModule(String viewName, String flowJson) {
        tx.run("save update_module", t -> {
            if (repo.get(t, NS_META, T_UPDATE_MODULE, Map.of("view_name", viewName)).isPresent()) {
                repo.update(t, NS_META, T_UPDATE_MODULE, Map.of("view_name", viewName),
                        Map.of("flow_json", flowJson, "updated_at", System.currentTimeMillis()));
            } else {
                repo.insert(t, NS_META, T_UPDATE_MODULE, Map.of(
                        "view_name", viewName,
                        "flow_json", flowJson,
                        "updated_at", System.currentTimeMillis()));
            }
            return null;
        });
    }

    public Optional<String> findModuleJson(String viewName) {
        return tx.run("get update_module", t ->
                        repo.get(t, NS_META, T_UPDATE_MODULE, Map.of("view_name", viewName)))
                .map(row -> (String) row.get("flow_json"));
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CustomException("JSON serialization failed: " + e.getMessage(), e, 500);
        }
    }
}
