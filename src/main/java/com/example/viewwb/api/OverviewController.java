package com.example.viewwb.api;

import com.example.viewwb.core.DynamicRepository;
import com.example.viewwb.core.TxRunner;
import com.example.viewwb.dto.ApiResponse;
import com.example.viewwb.meta.MetaSchema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GUI ポーリング用の俯瞰: outbox と宛先キュー(erp.re_inbox)の中身。
 * テーブルがまだ無い場合(RE 未セットアップ)は空リストを返す。
 *
 * 注: RE 配管テーブルは tx_state に SecondaryIndex を持ち、旧版 Cluster ノードでは
 * メタデータ返却が DB-CORE-10281 で壊れる(3.18 で修正済み — Cluster ノード 3.18+ が前提。
 * 経緯は e2e-report-20260719-plan005.md 発見3)。
 */
@RestController
@RequestMapping("/api/overview")
public class OverviewController {

    private final DynamicRepository repo;
    private final TxRunner tx;

    public OverviewController(DynamicRepository repo, TxRunner tx) {
        this.repo = repo;
        this.tx = tx;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> overview(
            @RequestParam(defaultValue = "erp") String destNamespace) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outbox", scanIfExists(MetaSchema.NS_META, MetaSchema.T_RE_OUTBOX));
        result.put("destInbox", scanIfExists(destNamespace, "re_inbox"));
        return ApiResponse.success(result);
    }

    private List<Map<String, Object>> scanIfExists(String namespace, String table) {
        if (repo.metadataOrNull(namespace, table) == null) {
            return List.of();
        }
        return tx.run("scan " + namespace + "." + table,
                t -> repo.scanAll(t, namespace, table));
    }
}
