package com.example.viewwb.service;

import com.example.viewwb.core.DynamicRepository;
import com.example.viewwb.meta.MetaSchema;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * セットアップ: viewmgr メタテーブルの作成。
 * viewmgr.re_outbox は本来 ScalarRE の --create-schema init が作るが、
 * RE を立てる前でも編集パス(via=RE)を動かせるよう、無ければ互換スキーマで作る。
 */
@Service
public class AdminSetupService {

    private static final Logger log = LoggerFactory.getLogger(AdminSetupService.class);

    private final DynamicRepository repo;

    public AdminSetupService(DynamicRepository repo) {
        this.repo = repo;
    }

    public Map<String, Object> setup() {
        MetaSchema.createAll(repo);
        if (repo.metadataOrNull(MetaSchema.NS_META, MetaSchema.T_RE_OUTBOX) == null) {
            repo.createTable(MetaSchema.NS_META, MetaSchema.T_RE_OUTBOX, TableMetadata.newBuilder()
                    .addColumn("event_type", DataType.TEXT)
                    .addColumn("event_id", DataType.TEXT)
                    .addColumn("body", DataType.TEXT)
                    .addColumn("created_at", DataType.BIGINT)
                    .addPartitionKey("event_type")
                    .addClusteringKey("event_id")
                    .build());
            log.info("created interim viewmgr.re_outbox (ScalarRE --create-schema will own it)");
        }
        log.info("viewmgr / views namespaces are ready");
        return Map.of("namespaces", new String[]{MetaSchema.NS_META, MetaSchema.NS_VIEWS});
    }
}
