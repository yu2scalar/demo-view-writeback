package com.example.viewwb.fc;

import com.example.viewwb.catalog.AnalyticsCatalogClient;
import com.example.viewwb.catalog.CatalogModel.CatalogInfo;
import com.example.viewwb.catalog.CatalogModel.ColumnInfo;
import com.example.viewwb.catalog.CatalogModel.DataSourceInfo;
import com.example.viewwb.catalog.CatalogModel.NamespaceInfo;
import com.example.viewwb.catalog.CatalogModel.TableInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * FC-1: Analytics カタログ DB 直読の実証。
 *
 * 実行: ./gradlew fc1
 * 合格基準: 4部名(catalog.datasource.namespace.table)+ カラム一覧 + 型を
 * Java オブジェクトに再構成できること。
 */
public class Fc1CatalogRead {

    private static final String DEFAULT_URL = "jdbc:postgresql://192.168.214.129:5432/scalardb_analytics";

    public static void main(String[] args) throws Exception {
        String url = System.getProperty("catalog.url", DEFAULT_URL);
        AnalyticsCatalogClient client = new AnalyticsCatalogClient(url, "scalaradmin", "scalaradmin");

        long start = System.currentTimeMillis();
        List<CatalogInfo> catalogs = client.readAll();
        long elapsed = System.currentTimeMillis() - start;

        int dsCount = 0;
        int nsCount = 0;
        int tableCount = 0;
        int colCount = 0;
        for (CatalogInfo c : catalogs) {
            for (DataSourceInfo ds : c.dataSources()) {
                dsCount++;
                for (NamespaceInfo ns : ds.namespaces()) {
                    nsCount++;
                    for (TableInfo t : ns.tables()) {
                        tableCount++;
                        colCount += t.columns().size();
                    }
                }
            }
        }
        System.out.printf("[FC-1] read %d catalog(s), %d datasource(s), %d namespace(s), %d table(s), %d column(s) in %d ms%n",
                catalogs.size(), dsCount, nsCount, tableCount, colCount, elapsed);

        for (CatalogInfo c : catalogs) {
            System.out.printf("catalog: %s (%s)%n", c.name(), c.catalogId());
            for (DataSourceInfo ds : c.dataSources()) {
                System.out.printf("  datasource: %s provider_type=%s namespaces=%d scalardb=%b%n",
                        ds.name(), ds.providerType(), ds.namespaces().size(), ds.isScalarDb());
                System.out.printf("    payload keys: %s%n", summarizePayload(ds.providerPayload()));
            }
        }

        // 4部名の再構成デモ: item_stock を両データソースから
        System.out.println();
        for (CatalogInfo c : catalogs) {
            for (DataSourceInfo ds : c.dataSources()) {
                for (NamespaceInfo ns : ds.namespaces()) {
                    for (TableInfo t : ns.tables()) {
                        if (!"item_stock".equals(t.name())) {
                            continue;
                        }
                        System.out.printf("%s.%s.%s.%s (provider=%s)%n",
                                c.name(), ds.name(), ns.displayName(), t.name(), ds.providerType());
                        for (ColumnInfo col : t.columns()) {
                            System.out.printf("    %2d %-25s %-12s nullable=%b%n",
                                    col.ordinalPosition(), col.name(), col.kind(), col.nullable());
                        }
                    }
                }
            }
        }
    }

    /** 接続情報の生値(パスワード等)を出さずに payload の構造だけ表示する */
    private static String summarizePayload(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        payload.fieldNames().forEachRemaining(f -> sb.append(f).append(' '));
        JsonNode configs = payload.path("configs");
        if (configs.isObject()) {
            sb.append("| configs: ").append(configs.size()).append(" entries");
        }
        return sb.toString().trim();
    }
}
