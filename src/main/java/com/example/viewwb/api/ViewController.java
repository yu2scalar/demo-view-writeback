package com.example.viewwb.api;

import com.example.viewwb.dto.ApiResponse;
import com.example.viewwb.engine.UpdateEngine;
import com.example.viewwb.meta.MetaRepository;
import com.example.viewwb.meta.ViewDefinition;
import com.example.viewwb.view.ViewService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** View 定義・行・更新モジュールの REST API。 */
@RestController
@RequestMapping("/api/views")
public class ViewController {

    private final ViewService viewService;
    private final UpdateEngine updateEngine;
    private final MetaRepository meta;

    public ViewController(ViewService viewService, UpdateEngine updateEngine, MetaRepository meta) {
        this.viewService = viewService;
        this.updateEngine = updateEngine;
        this.meta = meta;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(viewService.list());
    }

    /** View 作成 = 実体テーブル生成 + 初期マテリアライズ */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody ViewDefinition definition) {
        return ApiResponse.success(viewService.create(definition), "View created");
    }

    /** ビルダーの実行結果タブ: SQL 生成 + spark 実行のみ(保存しない) */
    @PostMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(@RequestBody ViewDefinition definition) {
        return ApiResponse.success(viewService.preview(definition));
    }

    /** 定義 + 行(クライアントはこの2点で動的 TableView を構成する) */
    @GetMapping("/{viewName}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String viewName) {
        return ApiResponse.success(viewService.definitionAndRows(viewName));
    }

    /** View 定義の置換(編集): 実体を作り直して再マテリアライズ。モジュールは維持 */
    @PutMapping("/{viewName}")
    public ApiResponse<Map<String, Object>> replace(@PathVariable String viewName,
                                                    @RequestBody ViewDefinition definition) {
        if (!viewName.equals(definition.viewName())) {
            throw new com.example.viewwb.exception.CustomException(
                    "viewName mismatch: path=" + viewName + " body=" + definition.viewName(), 400);
        }
        return ApiResponse.success(viewService.replace(definition), "View replaced");
    }

    @DeleteMapping("/{viewName}")
    public ApiResponse<Void> delete(@PathVariable String viewName) {
        viewService.delete(viewName);
        return ApiResponse.success("View deleted");
    }

    /** 手動リフレッシュ(入口はデータ編集画面のみ + REST 直接実行) */
    /** 選択値(plan-008): lookup 付き列の選択肢を一括返却(都度読み) */
    @GetMapping("/{viewName}/lookups")
    public ApiResponse<Map<String, List<Map<String, Object>>>> lookups(
            @PathVariable String viewName) {
        return ApiResponse.success(viewService.lookups(viewName));
    }

    @PostMapping("/{viewName}/refresh")
    public ApiResponse<Map<String, Object>> refresh(@PathVariable String viewName) {
        return ApiResponse.success(viewService.refresh(viewName), "View refreshed");
    }

    /** 行更新: 更新モジュール(未定義なら素通し)を通して write-back */
    @PutMapping("/{viewName}/rows")
    public ApiResponse<Map<String, Object>> updateRow(@PathVariable String viewName,
                                                      @RequestBody Map<String, Object> row) {
        return ApiResponse.success(updateEngine.update(viewName, row));
    }

    // ---- 更新モジュール -----------------------------------------------------

    @GetMapping("/{viewName}/module")
    public ApiResponse<Map<String, Object>> getModule(@PathVariable String viewName) {
        meta.viewDefRow(viewName); // 404 チェック
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("viewName", viewName);
        body.put("flowJson", meta.findModuleJson(viewName).orElse(null));
        return ApiResponse.success(body);
    }

    @PutMapping("/{viewName}/module")
    public ApiResponse<Void> saveModule(@PathVariable String viewName, @RequestBody String flowJson) {
        meta.viewDefRow(viewName); // 404 チェック
        if (meta.viewDef(viewName).isAggregate()) {
            throw new com.example.viewwb.exception.CustomException(
                    "集計 view は read-only のため更新モジュールを登録できません: " + viewName, 400);
        }
        meta.saveModule(viewName, flowJson);
        return ApiResponse.success("Update module saved");
    }
}
