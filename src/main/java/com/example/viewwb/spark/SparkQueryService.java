package com.example.viewwb.spark;

import com.example.viewwb.exception.CustomException;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * spark-connect(Analytics)への窓口。SparkSession は生成コストが高いため共有し、
 * 障害時は作り直す。結果は列名→値の Map リストに落とす(値の型変換は
 * ValueCodec に委ねられるよう素の Java 型のまま返す:
 * java.sql.Date / java.sql.Timestamp / LocalDateTime / byte[] など)。
 */
@Service
public class SparkQueryService {

    private static final Logger log = LoggerFactory.getLogger(SparkQueryService.class);

    private final String remote;
    private SparkSession session;

    public SparkQueryService(@Value("${app.spark.remote}") String remote) {
        this.remote = remote;
    }

    public synchronized List<Map<String, Object>> query(String sql) {
        try {
            return run(sql);
        } catch (RuntimeException first) {
            // セッション切れ等は一度だけ作り直して再試行
            log.warn("spark query failed, recreating session: {}", first.getMessage());
            closeQuietly();
            try {
                return run(sql);
            } catch (RuntimeException second) {
                throw new CustomException("Spark クエリが失敗しました: " + second.getMessage(), second, 502);
            }
        }
    }

    private List<Map<String, Object>> run(String sql) {
        SparkSession spark = ensureSession();
        Dataset<Row> df = spark.sql(sql);
        String[] columns = df.columns();
        Row[] rows = (Row[]) df.collect();
        List<Map<String, Object>> result = new ArrayList<>(rows.length);
        for (Row row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < columns.length; i++) {
                map.put(columns[i], row.isNullAt(i) ? null : row.get(i));
            }
            result.add(map);
        }
        return result;
    }

    private SparkSession ensureSession() {
        if (session == null) {
            log.info("connecting spark-connect: {}", remote);
            session = SparkSession.builder().remote(remote).create();
        }
        return session;
    }

    private void closeQuietly() {
        if (session != null) {
            try {
                session.stop();
            } catch (RuntimeException e) {
                log.debug("ignoring spark session close failure: {}", e.getMessage());
            }
            session = null;
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        closeQuietly();
    }
}
