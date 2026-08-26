package com.example.viewwb.fc;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

/**
 * FC-2: spark-connect 経由のクエリ実行の実証。
 *
 * 実行: ./gradlew fc2
 * 合格基準: 単表 SELECT とデータソース横断 JOIN の結果を Java 側で List&lt;Row&gt; として取得できること。
 * サーバー: Spark 3.5.6 standalone(192.168.214.129、spark-connect :15002)
 */
public class Fc2SparkConnect {

    private static final String DEFAULT_REMOTE = "sc://192.168.214.129:15002";

    public static void main(String[] args) {
        String remote = System.getProperty("spark.remote", DEFAULT_REMOTE);
        System.out.printf("[FC-2] connecting to %s%n", remote);

        SparkSession spark = SparkSession.builder().remote(remote).create();
        try {
            System.out.printf("[FC-2] server spark version: %s%n", spark.version());

            // 1) 単表 SELECT(ユーザー提供のサンプルと同じテーブル)
            String single = "SELECT id, item_group_id, item_stock_qty"
                    + " FROM scalardb_catalog.ds_scalardb.ns_mysql.item_stock ORDER BY id LIMIT 5";
            runQuery(spark, "single-table", single);

            // 2) ストレージ横断 JOIN(MySQL 側と PostgreSQL 側の item_stock を id で結合)
            String join = "SELECT m.id, m.item_group_id, m.item_stock_qty AS mysql_qty,"
                    + " p.item_stock_qty AS postgres_qty"
                    + " FROM scalardb_catalog.ds_scalardb.ns_mysql.item_stock m"
                    + " JOIN scalardb_catalog.ds_scalardb.ns_postgres.item_stock p ON m.id = p.id"
                    + " ORDER BY m.id LIMIT 10";
            runQuery(spark, "cross-storage join", join);
        } finally {
            spark.stop();
        }
    }

    private static void runQuery(SparkSession spark, String label, String sql) {
        System.out.printf("%n[FC-2] %s:%n%s%n", label, sql);
        long start = System.currentTimeMillis();
        Dataset<Row> df = spark.sql(sql);
        Row[] rows = (Row[]) df.collect();
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("schema: %s%n", df.schema().treeString());
        for (Row row : rows) {
            System.out.println("  " + row);
        }
        System.out.printf("=> %d row(s) in %d ms%n", rows.length, elapsed);
    }
}
