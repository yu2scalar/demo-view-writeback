package com.example.viewwb.config;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.service.TransactionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * ScalarDB Cluster への接続(plan-005 T0)。接続情報はローカルの properties ファイルから
 * 構築する。Spark(Analytics)が使う ds_scalardb カタログ登録とは独立 —
 * 書き込みは必ず Cluster 経由、Analytics 直結は read-only という規律(plan-004 フェーズ2考慮)。
 */
@Configuration
public class ScalarDbConfig {

    @Bean
    public TransactionFactory transactionFactory(
            @Value("${app.scalardb.properties:config/scalardb.properties}") String propertiesPath)
            throws IOException {
        return TransactionFactory.create(propertiesPath);
    }

    @Bean
    public DistributedTransactionManager distributedTransactionManager(TransactionFactory factory) {
        return factory.getTransactionManager();
    }

    @Bean(destroyMethod = "close")
    public DistributedTransactionAdmin distributedTransactionAdmin(TransactionFactory factory) {
        return factory.getTransactionAdmin();
    }
}
