package com.example.viewwb.view;

import com.example.viewwb.core.DynamicRepository;
import com.example.viewwb.core.KeyConcat;
import com.example.viewwb.core.TxRunner;
import com.example.viewwb.core.ValueCodec;
import com.example.viewwb.exception.CustomException;
import com.example.viewwb.meta.MetaRepository;
import com.example.viewwb.meta.MetaSchema;
import com.example.viewwb.meta.ViewDefinition;
import com.example.viewwb.meta.ViewDefinition.ColumnDef;
import com.example.viewwb.spark.SparkQueryService;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * View のライフサイクル: 作成(実体テーブル生成 + 初期マテリアライズ)/ 一覧 / 行取得 /
 * refresh(spark 再クエリ → truncate → 投入)/ 削除。
 *
 * マテリアライズは spark-connect の JOIN 結果を views.<view_name> に書き込む。
 * デモ規模のため 1 Tx でまとめて書く(実運用のチャンク分割は口頭説明)。
 */
@Service
public class ViewService {

    private static final Logger log = LoggerFactory.getLogger(ViewService.class);

    private final MetaRepository meta;
    private final DynamicRepository repo;
    private final TxRunner tx;
    private final SparkQueryService spark;
    private final SqlGenerator sqlGenerator;

    public ViewService(MetaRepository meta, DynamicRepository repo, TxRunner tx,
                       SparkQueryService spark, SqlGenerator sqlGenerator) {
        this.meta = meta;
        this.repo = repo;
        this.tx = tx;
        this.spark = spark;
        this.sqlGenerator = sqlGenerator;
    }

    /** ビルダーの実行結果タブ用プレビュー: SQL 生成 → spark 実行のみ(実体は作らない) */
    public Map<String, Object> preview(ViewDefinition def) {
        def.validate();
        String sql = sqlGenerator.selectSql(def);
        List<Map<String, Object>> rows = spark.query(sql + "\nLIMIT " + PREVIEW_LIMIT);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sql", sql);
        result.put("rowCount", rows.size());
        result.put("limited", rows.size() >= PREVIEW_LIMIT);
        result.put("limit", PREVIEW_LIMIT);
        result.put("rows", rows);
        return result;
    }

    private static final int PREVIEW_LIMIT = 100;

    /**
     * lookup ソースの実在検証(plan-008)。ソースは Cluster 経由で読める範囲
     * (ScalarDB 管理テーブル + view 実体)に限る — メタデータが引ければ読める。
     */
    private void validateLookups(ViewDefinition def) {
        for (ViewDefinition.ColumnDef c : def.columns()) {
            if (c.lookup() == null) {
                continue;
            }
            ViewDefinition.LookupDef lu = c.lookup();
            TableMetadata sourceMeta = repo.metadataOrNull(lu.namespace(), lu.table());
            if (sourceMeta == null) {
                throw new CustomException("列 '" + c.viewColumn() + "' の lookup ソース "
                        + lu.namespace() + "." + lu.table() + " が見つかりません"
                        + "(ScalarDB 管理テーブルまたは view 実体のみ指定できます)", 400);
            }
            for (String col : List.of(lu.keyColumn(), lu.labelColumn())) {
                if (!sourceMeta.getColumnNames().contains(col)) {
                    throw new CustomException("lookup ソース " + lu.namespace() + "."
                            + lu.table() + " に列 '" + col + "' がありません", 400);
                }
            }
        }
    }

    /** lookup 付き列の選択肢を一括返却: viewColumn → [{value, label}](都度読み) */
    public Map<String, List<Map<String, Object>>> lookups(String viewName) {
        Map<String, Object> row = meta.viewDefRow(viewName);
        ViewDefinition def = meta.parseDefinition((String) row.get("definition_json"));
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (ViewDefinition.ColumnDef c : def.columns()) {
            if (c.lookup() == null) {
                continue;
            }
            ViewDefinition.LookupDef lu = c.lookup();
            List<Map<String, Object>> rows = tx.run("lookup " + lu.namespace() + "." + lu.table(),
                    t -> repo.scanAll(t, lu.namespace(), lu.table()));
            List<Map<String, Object>> options = rows.stream()
                    .limit(LOOKUP_LIMIT)
                    .map(r -> {
                        Map<String, Object> o = new LinkedHashMap<String, Object>();
                        o.put("value", r.get(lu.keyColumn()));
                        o.put("label", r.get(lu.labelColumn()));
                        return o;
                    })
                    .toList();
            result.put(c.viewColumn(), options);
            if (rows.size() > LOOKUP_LIMIT) {
                log.warn("lookup {}.{} exceeded {} rows — truncated",
                        lu.namespace(), lu.table(), LOOKUP_LIMIT);
            }
        }
        return result;
    }

    private static final int LOOKUP_LIMIT = 1000;

    /** View 作成: 定義検証 → 実体テーブル作成 → メタ保存 → 初期マテリアライズ */
    public Map<String, Object> create(ViewDefinition def) {
        def.validate();
        validateLookups(def);
        boolean exists = tx.run("check view exists",
                t -> meta.findViewDefRow(t, def.viewName()).isPresent());
        if (exists) {
            throw new CustomException("View は既に存在します: " + def.viewName(), 409);
        }
        String sql = sqlGenerator.selectSql(def);

        // spark クエリを先に実行し、成功してから実体を作る(失敗時に中途半端な View を残さない)
        List<Map<String, Object>> sparkRows = spark.query(sql);
        repo.createOrReplaceTable(MetaSchema.NS_VIEWS, def.viewName(), entityMetadata(def));
        tx.run("insert view_def", t -> {
            meta.insertViewDef(t, def, sql);
            return null;
        });
        int rows = insertRows(def, sparkRows);
        log.info("view {} created and materialized: {} rows", def.viewName(), rows);
        return Map.of("viewName", def.viewName(), "rows", rows, "sql", sql);
    }

    /**
     * View 定義の置換(編集): 実体テーブルを作り直して再マテリアライズ。
     * 更新モジュールは残す(参照列が消えている可能性があるため警告を返す)。
     */
    public Map<String, Object> replace(ViewDefinition def) {
        def.validate();
        validateLookups(def);
        meta.viewDefRow(def.viewName()); // 404 チェック
        String sql = sqlGenerator.selectSql(def);

        // spark クエリを先に実行し、成功してから既存実体を作り直す(失敗時に旧データを失わない)
        List<Map<String, Object>> sparkRows = spark.query(sql);
        repo.dropTable(MetaSchema.NS_VIEWS, def.viewName());
        repo.createOrReplaceTable(MetaSchema.NS_VIEWS, def.viewName(), entityMetadata(def));
        tx.run("update view_def", t -> {
            meta.updateViewDef(t, def, sql);
            return null;
        });
        int rows = insertRows(def, sparkRows);
        boolean moduleExists = meta.findModuleJson(def.viewName()).isPresent();
        log.info("view {} replaced and rematerialized: {} rows", def.viewName(), rows);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("viewName", def.viewName());
        result.put("rows", rows);
        result.put("sql", sql);
        if (moduleExists) {
            result.put("moduleWarning",
                    "更新モジュールは維持されています。列構成を変えた場合は参照列を確認してください");
        }
        return result;
    }

    /** refresh: spark 再クエリ(成功後に truncate → 投入。失敗時に現データを消さない) */
    public Map<String, Object> refresh(String viewName) {
        Map<String, Object> row = meta.viewDefRow(viewName);
        ViewDefinition def = meta.parseDefinition((String) row.get("definition_json"));
        List<Map<String, Object>> sparkRows = spark.query((String) row.get("sql_text"));
        repo.truncateTable(MetaSchema.NS_VIEWS, viewName);
        int rows = insertRows(def, sparkRows);
        log.info("view {} refreshed: {} rows", viewName, rows);
        return Map.of("viewName", viewName, "rows", rows);
    }

    public void delete(String viewName) {
        meta.viewDefRow(viewName); // 404 チェック
        tx.run("delete view_def", t -> {
            meta.deleteViewDef(t, viewName);
            return null;
        });
        repo.dropTable(MetaSchema.NS_VIEWS, viewName);
        log.info("view {} deleted", viewName);
    }

    public List<Map<String, Object>> list() {
        return meta.listViewDefRows().stream()
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("viewName", row.get("view_name"));
                    item.put("status", row.get("status"));
                    item.put("createdAt", row.get("created_at"));
                    item.put("refreshedAt", row.get("refreshed_at"));
                    item.put("hasModule",
                            meta.findModuleJson((String) row.get("view_name")).isPresent());
                    return item;
                })
                .toList();
    }

    /** 定義 + 行(ソート順適用済み)。クライアントはこの2点で動的 TableView を組む */
    public Map<String, Object> definitionAndRows(String viewName) {
        Map<String, Object> defRow = meta.viewDefRow(viewName);
        ViewDefinition def = meta.parseDefinition((String) defRow.get("definition_json"));
        List<Map<String, Object>> rows = new ArrayList<>(tx.run("scan view rows",
                t -> repo.scanAll(t, MetaSchema.NS_VIEWS, viewName)));
        sortRows(def, rows);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("definition", def);
        result.put("sql", defRow.get("sql_text"));
        result.put("refreshedAt", defRow.get("refreshed_at"));
        result.put("rows", rows);
        return result;
    }

    // ---- internal ----------------------------------------------------------

    /**
     * View 実体のテーブル定義(plan-005): PK = テーブルごとの内部連結キー列 <alias>_pk の複合
     * (配置順で先頭がパーティションキー、残りがクラスタリングキー)。本来のキーカラムは通常列。
     * クラスタリング側の <alias>_pk のみ SecondaryIndex を張り、更新伝播の逆引きに使う
     * (パーティションキー列に index を張ると主キー Get が DB-CORE-10003 で壊れるため —
     * design-note-mv-maintenance.md 決定欄)。
     */
    private TableMetadata entityMetadata(ViewDefinition def) {
        TableMetadata.Builder builder = TableMetadata.newBuilder();
        for (ColumnDef c : def.columns()) {
            if (!c.visible() && !c.isKey()) {
                continue;
            }
            builder.addColumn(c.viewColumn(), dataType(c));
        }
        List<String> pkColumns = def.pkColumns();
        pkColumns.forEach(pk -> builder.addColumn(pk, DataType.TEXT));
        builder.addPartitionKey(pkColumns.get(0));
        for (int i = 1; i < pkColumns.size(); i++) {
            builder.addClusteringKey(pkColumns.get(i));
            builder.addSecondaryIndex(pkColumns.get(i));
        }
        return builder.build();
    }

    private DataType dataType(ColumnDef c) {
        try {
            return DataType.valueOf(c.kind().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CustomException("Unknown column kind '" + c.kind() + "' for "
                    + c.viewColumn(), 400);
        }
    }

    /** spark-connect の取得結果を views.<view> に投入(クエリは呼び出し側で実行済み) */
    private int insertRows(ViewDefinition def, List<Map<String, Object>> sparkRows) {
        Map<String, DataType> kinds = new LinkedHashMap<>();
        def.columns().stream()
                .filter(c -> c.visible() || c.isKey())
                .forEach(c -> kinds.put(c.viewColumn(), dataType(c)));

        tx.run("materialize " + def.viewName(), t -> {
            for (Map<String, Object> sparkRow : sparkRows) {
                Map<String, Object> values = new LinkedHashMap<>();
                for (Map.Entry<String, DataType> e : kinds.entrySet()) {
                    values.put(e.getKey(),
                            ValueCodec.parse(e.getKey(), e.getValue(), sparkRow.get(e.getKey())));
                }
                // 内部連結キー <alias>_pk(実体 PK)。ソースキー列は TableRef.keyColumns 順で連結
                for (ViewDefinition.TableRef table : def.tables()) {
                    values.put(ViewDefinition.pkColumn(table.alias()), KeyConcat.encode(values,
                            def.keyColumnsOf(table.alias()).stream()
                                    .map(ColumnDef::viewColumn).toList()));
                }
                repo.insert(t, MetaSchema.NS_VIEWS, def.viewName(), values);
            }
            meta.touchRefreshedAt(t, def.viewName());
            return null;
        });
        return sparkRows.size();
    }

    /** sortOrder 指定列で並べ替え(ScalarDB の scan all は順序保証がないためアプリ側で) */
    private void sortRows(ViewDefinition def, List<Map<String, Object>> rows) {
        List<ColumnDef> sorts = new ArrayList<>(def.columns().stream()
                .filter(c -> c.sortOrder() != null && c.sortOrder() > 0)
                .sorted(Comparator.comparingInt(ColumnDef::sortOrder))
                .toList());
        if (sorts.isEmpty()) {
            sorts.addAll(def.keyColumns());
        }
        Comparator<Map<String, Object>> comparator = null;
        for (ColumnDef c : sorts) {
            Comparator<Map<String, Object>> next = Comparator.comparing(
                    row -> asComparable(row.get(c.viewColumn())),
                    Comparator.nullsLast(Comparator.naturalOrder()));
            if ("DESC".equalsIgnoreCase(c.sortDir())) {
                next = next.reversed();
            }
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        rows.sort(comparator);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Comparable asComparable(Object value) {
        return value instanceof Comparable c ? c : value == null ? null : String.valueOf(value);
    }
}
