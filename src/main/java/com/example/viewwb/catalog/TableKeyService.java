package com.example.viewwb.catalog;

import com.example.viewwb.catalog.CatalogModel.DataSourceInfo;
import com.example.viewwb.core.DynamicRepository;
import com.scalar.db.api.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * テーブルのキー(PK)を遅延解決する。カタログは PK を持たないため:
 * <ol>
 *   <li>ScalarDB 管理テーブル → getTableMetadata の partition/clustering key。</li>
 *   <li>非 ScalarDB → data_source の接続情報から {@link KeyResolver} で PK を取得
 *       (partitionKeys にまとめて格納、clusteringKeys は空)。</li>
 *   <li>いずれも取れなければ known=false(GUI 手動指定にフォールバック)。</li>
 * </ol>
 * plan-010。
 */
@Service
public class TableKeyService {

    private static final Logger log = LoggerFactory.getLogger(TableKeyService.class);

    private final DynamicRepository repo;
    private final CatalogService catalogService;
    private final List<KeyResolver> keyResolvers;

    public TableKeyService(DynamicRepository repo, CatalogService catalogService,
                           List<KeyResolver> keyResolvers) {
        this.repo = repo;
        this.catalogService = catalogService;
        this.keyResolvers = keyResolvers;
    }

    /** キー解決結果。known=false のとき列リストは空。 */
    public record TableKeys(boolean known, List<String> partitionKeys, List<String> clusteringKeys) {
        static TableKeys unknown() {
            return new TableKeys(false, List.of(), List.of());
        }
    }

    /**
     * @param dataSource カタログ上のデータソース名(非 SDB 解決に必要。null 可)
     * @param namespace  名前空間 displayName(= PG schema / MySQL database)
     * @param table      テーブル名
     */
    public TableKeys resolve(String dataSource, String namespace, String table) {
        // 1. ScalarDB 管理テーブル
        TableMetadata meta = repo.metadataOrNull(namespace, table);
        if (meta != null) {
            return new TableKeys(true,
                    List.copyOf(meta.getPartitionKeyNames()),
                    List.copyOf(meta.getClusteringKeyNames()));
        }

        // 2. 非 ScalarDB: 接続情報から PK を自動取得
        if (dataSource != null) {
            DataSourceInfo ds = catalogService.dataSource(dataSource);
            if (ds != null && !ds.isScalarDb()) {
                KeyResolver resolver = keyResolvers.stream()
                        .filter(r -> r.supports(ds.providerType()))
                        .findFirst()
                        .orElse(null);
                if (resolver == null) {
                    log.info("[key-resolve] no resolver for provider '{}' ({}.{}) — manual fallback",
                            ds.providerType(), namespace, table);
                } else {
                    List<String> pk = resolver.primaryKeyColumns(ds.providerPayload(), namespace, table);
                    if (!pk.isEmpty()) {
                        return new TableKeys(true, pk, List.of());
                    }
                }
            }
        }

        // 3. フォールバック(GUI 手動指定)
        return TableKeys.unknown();
    }
}
