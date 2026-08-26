package com.example.viewwb.api;

import com.example.viewwb.catalog.CatalogService;
import com.example.viewwb.catalog.TableKeyService;
import com.example.viewwb.catalog.TableKeyService.TableKeys;
import com.example.viewwb.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GUI 左ペイン用のカタログツリー(接続情報・パスワードは含めない)。 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogService catalogService;
    private final TableKeyService tableKeyService;

    public CatalogController(CatalogService catalogService, TableKeyService tableKeyService) {
        this.catalogService = catalogService;
        this.tableKeyService = tableKeyService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> tree(
            @RequestParam(defaultValue = "false") boolean reload) {
        catalogService.catalogs(reload);
        return ApiResponse.success(catalogService.tree());
    }

    /**
     * キー情報の遅延取得(カタログにはキー情報が無いため)。
     * scalardb テーブルは getTableMetadata で補完、非 scalardb テーブルは data_source の
     * 接続情報から PK を自動取得する(plan-010)。いずれも取れなければ known=false を返し、
     * GUI 側でユーザーがキー列を手動指定する。
     */
    @GetMapping("/table-keys")
    public ApiResponse<Map<String, Object>> tableKeys(
            @RequestParam(required = false) String dataSource,
            @RequestParam String namespace, @RequestParam String table) {
        TableKeys keys = tableKeyService.resolve(dataSource, namespace, table);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("known", keys.known());
        result.put("partitionKeys", keys.partitionKeys());
        result.put("clusteringKeys", keys.clusteringKeys());
        return ApiResponse.success(result);
    }
}
