package com.example.viewwb.meta;

import com.example.viewwb.core.DynamicRepository;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;

/**
 * viewmgr メタテーブルと views namespace(v2)。
 * バックエンド定義テーブルは持たない(Analytics カタログが代替)。
 * RE 配管テーブル(viewmgr.re_outbox、宛先 re_inbox、scalarre.*)は
 * ScalarRE の --create-schema init が作る(アプリでは作らない)。
 */
public final class MetaSchema {

    /** メタデータ + producer outbox の namespace */
    public static final String NS_META = "viewmgr";
    /** View 実体(マテリアライズ結果)の namespace */
    public static final String NS_VIEWS = "views";

    public static final String T_VIEW_DEF = "view_def";
    public static final String T_UPDATE_MODULE = "update_module";
    public static final String T_RE_OUTBOX = "re_outbox";

    private MetaSchema() {
    }

    /** namespace とメタテーブルを作成(冪等) */
    public static void createAll(DynamicRepository repository) {
        repository.createNamespace(NS_META);
        repository.createNamespace(NS_VIEWS);

        repository.createOrReplaceTable(NS_META, T_VIEW_DEF, TableMetadata.newBuilder()
                .addColumn("view_name", DataType.TEXT)
                .addColumn("definition_json", DataType.TEXT)
                .addColumn("sql_text", DataType.TEXT)
                .addColumn("status", DataType.TEXT)
                .addColumn("created_at", DataType.BIGINT)
                .addColumn("refreshed_at", DataType.BIGINT)
                .addPartitionKey("view_name")
                .build());

        repository.createOrReplaceTable(NS_META, T_UPDATE_MODULE, TableMetadata.newBuilder()
                .addColumn("view_name", DataType.TEXT)
                .addColumn("flow_json", DataType.TEXT)
                .addColumn("updated_at", DataType.BIGINT)
                .addPartitionKey("view_name")
                .build());
    }
}
