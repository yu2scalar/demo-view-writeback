package com.example.viewwb.api;

import com.example.viewwb.catalog.CatalogService;
import com.example.viewwb.core.DynamicRepository;
import com.example.viewwb.dto.ApiResponse;
import com.scalar.db.api.TableMetadata;
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
    private final DynamicRepository repo;

    public CatalogController(CatalogService catalogService, DynamicRepository repo) {
        this.catalogService = catalogService;
        this.repo = repo;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> tree(
            @RequestParam(defaultValue = "false") boolean reload) {
        catalogService.catalogs(reload);
        return ApiResponse.success(catalogService.tree());
    }

    /**
     * キー情報の遅延取得(カタログにはキー情報が無いため)。
     * scalardb テーブルは getTableMetadata で補完、それ以外は known=false を返し
     * GUI 側でユーザーがキー列を指定する。
     */
    @GetMapping("/table-keys")
    public ApiResponse<Map<String, Object>> tableKeys(
            @RequestParam String namespace, @RequestParam String table) {
        Map<String, Object> result = new LinkedHashMap<>();
        TableMetadata meta = repo.metadataOrNull(namespace, table);
        if (meta == null) {
            result.put("known", false);
            result.put("partitionKeys", List.of());
            result.put("clusteringKeys", List.of());
        } else {
            result.put("known", true);
            result.put("partitionKeys", List.copyOf(meta.getPartitionKeyNames()));
            result.put("clusteringKeys", List.copyOf(meta.getClusteringKeyNames()));
        }
        return ApiResponse.success(result);
    }
}
