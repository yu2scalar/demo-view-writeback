package com.example.viewwb.fc;

import com.example.viewwb.catalog.AnalyticsCatalogClient;
import com.example.viewwb.catalog.CatalogModel.CatalogInfo;
import com.example.viewwb.catalog.CatalogModel.DataSourceInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Put;
import com.scalar.db.api.Result;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.Key;
import com.scalar.db.service.TransactionFactory;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * FC-3: ds_scalardb の provider_payload_json の configs をそのまま ScalarDB properties として使い、
 * ライブラリモードで Tx 書き戻しができることの実証。
 *
 * 実行: ./gradlew fc3
 * 合格基準:
 *  1. カタログ由来の接続情報だけで DistributedTransactionManager が作れる(接続情報の二重管理が不要)
 *  2. getTableMetadata でパーティションキー/クラスタリングキーが取得できる(カタログに無いキー情報の補完手段)
 *  3. Tx コミット成功 + spark-connect 再クエリで更新値が見える
 * 対象: ns_mysql.item_stock の id=100 の item_stock_qty を +1 → 確認 → 元値に戻す
 */
public class Fc3ScalarDbWriteback {

    private static final String CATALOG_URL = "jdbc:postgresql://192.168.214.129:5432/scalardb_analytics";
    private static final String SPARK_REMOTE = "sc://192.168.214.129:15002";
    private static final String NAMESPACE = "ns_mysql";
    private static final String TABLE = "item_stock";
    private static final int TARGET_ID = 100;
    /** id=100 の行のクラスタリングキー値(getTableMetadata で clusteringKey=[item_group_id] と判明) */
    private static final int TARGET_GROUP_ID = 1000;

    public static void main(String[] args) throws Exception {
        // 1. カタログから ds_scalardb の configs を取り出して Properties 化
        AnalyticsCatalogClient catalogClient =
                new AnalyticsCatalogClient(CATALOG_URL, "scalaradmin", "scalaradmin");
        List<CatalogInfo> catalogs = catalogClient.readAll();
        DataSourceInfo scalarDbDs = catalogs.stream()
                .flatMap(c -> c.dataSources().stream())
                .filter(DataSourceInfo::isScalarDb)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no scalardb datasource in catalog"));

        Properties props = new Properties();
        JsonNode configs = scalarDbDs.providerPayload().path("configs");
        configs.fields().forEachRemaining(e -> props.setProperty(e.getKey(), e.getValue().asText()));
        System.out.printf("[FC-3] built %d ScalarDB properties from catalog datasource '%s' (no local config files)%n",
                props.size(), scalarDbDs.name());

        TransactionFactory factory = TransactionFactory.create(props);

        // 2. getTableMetadata でキー情報を取得(カタログに存在しないため、この経路が設計上の補完手段)
        try (DistributedTransactionAdmin admin = factory.getTransactionAdmin()) {
            TableMetadata meta = admin.getTableMetadata(NAMESPACE, TABLE);
            if (meta == null) {
                throw new IllegalStateException(NAMESPACE + "." + TABLE + " has no ScalarDB metadata");
            }
            System.out.printf("[FC-3] %s.%s metadata: partitionKey=%s clusteringKey=%s columns=%s%n",
                    NAMESPACE, TABLE,
                    meta.getPartitionKeyNames(), meta.getClusteringKeyNames(), meta.getColumnNames());
        }

        // 3. Tx で +1 更新 → spark-connect で見えることを確認 → 元値に戻す
        DistributedTransactionManager tm = factory.getTransactionManager();
        try {
            int original = readQty(tm);
            System.out.printf("[FC-3] current qty(id=%d) = %d%n", TARGET_ID, original);

            writeQty(tm, original + 1);
            System.out.printf("[FC-3] committed qty -> %d via ScalarDB Tx%n", original + 1);

            int seenBySpark = readQtyViaSpark();
            System.out.printf("[FC-3] spark-connect sees qty = %d (%s)%n", seenBySpark,
                    seenBySpark == original + 1 ? "OK: 更新が Analytics 経由で見える" : "NG: 不一致");

            writeQty(tm, original);
            System.out.printf("[FC-3] restored qty -> %d%n", original);

            if (seenBySpark != original + 1) {
                System.exit(1);
            }
            System.out.println("[FC-3] PASS");
        } finally {
            tm.close();
        }
    }

    private static int readQty(DistributedTransactionManager tm) throws Exception {
        DistributedTransaction tx = tm.start();
        try {
            Optional<Result> result = tx.get(
                    Get.newBuilder()
                            .namespace(NAMESPACE).table(TABLE)
                            .partitionKey(Key.ofInt("id", TARGET_ID))
                            .clusteringKey(Key.ofInt("item_group_id", TARGET_GROUP_ID))
                            .build());
            tx.commit();
            return result.orElseThrow(() -> new IllegalStateException("row not found: id=" + TARGET_ID))
                    .getInt("item_stock_qty");
        } catch (Exception e) {
            tx.abort();
            throw e;
        }
    }

    private static void writeQty(DistributedTransactionManager tm, int qty) throws Exception {
        DistributedTransaction tx = tm.start();
        try {
            tx.put(Put.newBuilder()
                    .namespace(NAMESPACE).table(TABLE)
                    .partitionKey(Key.ofInt("id", TARGET_ID))
                    .clusteringKey(Key.ofInt("item_group_id", TARGET_GROUP_ID))
                    .intValue("item_stock_qty", qty)
                    .enableImplicitPreRead()
                    .build());
            tx.commit();
        } catch (Exception e) {
            tx.abort();
            throw e;
        }
    }

    private static int readQtyViaSpark() {
        SparkSession spark = SparkSession.builder().remote(SPARK_REMOTE).create();
        try {
            Row[] rows = (Row[]) spark.sql(
                    "SELECT item_stock_qty FROM scalardb_catalog.ds_scalardb.ns_mysql.item_stock WHERE id = "
                            + TARGET_ID).collect();
            if (rows.length != 1) {
                throw new IllegalStateException("unexpected row count: " + rows.length);
            }
            return rows[0].getInt(0);
        } finally {
            spark.stop();
        }
    }
}
