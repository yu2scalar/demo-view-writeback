package com.example.viewwb.core;

import com.example.viewwb.exception.CustomException;
import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.Get;
import com.scalar.db.api.Insert;
import com.scalar.db.api.InsertBuilder;
import com.scalar.db.api.Scan;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.Update;
import com.scalar.db.api.UpdateBuilder;
import com.scalar.db.exception.storage.ExecutionException;
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.io.Key;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metadata-driven data access: every table (registered backends, viewmgr
 * metadata, materialized view entities) is read and written through the same
 * generic map-based operations, typed via cached TableMetadata. This is the
 * layer M2's rule evaluation and M3's flow execution will build on.
 */
@Component
public class DynamicRepository {

    private final DistributedTransactionAdmin admin;
    private final Map<String, TableMetadata> metadataCache = new ConcurrentHashMap<>();

    public DynamicRepository(DistributedTransactionAdmin admin) {
        this.admin = admin;
    }

    /** Returns the table's metadata (cached), or throws 404 when absent. */
    public TableMetadata metadata(String namespace, String table) {
        String cacheKey = namespace + "." + table;
        TableMetadata cached = metadataCache.computeIfAbsent(cacheKey, k -> {
            try {
                return admin.getTableMetadata(namespace, table);
            } catch (ExecutionException e) {
                throw new CustomException("Failed to read metadata of " + k + ": "
                        + e.getMessage(), e, 500);
            }
        });
        if (cached == null) {
            metadataCache.remove(cacheKey);
            throw new CustomException("Table " + cacheKey + " does not exist", 404);
        }
        return cached;
    }

    /** Returns the table's metadata without caching, null when absent. */
    public TableMetadata metadataOrNull(String namespace, String table) {
        try {
            return admin.getTableMetadata(namespace, table);
        } catch (ExecutionException e) {
            throw new CustomException("Failed to read metadata of " + namespace + "." + table
                    + ": " + e.getMessage(), e, 500);
        }
    }

    public void invalidateMetadata(String namespace, String table) {
        metadataCache.remove(namespace + "." + table);
    }

    // ---- reads -----------------------------------------------------------

    public List<Map<String, Object>> scanAll(DistributedTransaction tx, String namespace, String table)
            throws TransactionException {
        return tx.scan(Scan.newBuilder().namespace(namespace).table(table).all().build())
                .stream()
                .map(ValueCodec::toMap)
                .toList();
    }

    /** Scans a single partition (all clustering-key rows under the partition key). */
    public List<Map<String, Object>> scanPartition(DistributedTransaction tx, String namespace,
                                                   String table, Map<String, Object> partitionKeyValues)
            throws TransactionException {
        TableMetadata meta = metadata(namespace, table);
        Key partitionKey = ValueCodec.buildKey(meta,
                new ArrayList<>(meta.getPartitionKeyNames()), partitionKeyValues);
        return tx.scan(Scan.newBuilder().namespace(namespace).table(table)
                        .partitionKey(partitionKey).build())
                .stream()
                .map(ValueCodec::toMap)
                .toList();
    }

    /** SecondaryIndex の等価スキャン(plan-005: 更新伝播の逆引きに使用) */
    public List<Map<String, Object>> scanByIndex(DistributedTransaction tx, String namespace,
                                                 String table, String column, Object value)
            throws TransactionException {
        TableMetadata meta = metadata(namespace, table);
        Key indexKey = Key.newBuilder()
                .add(ValueCodec.toColumn(column, meta.getColumnDataType(column), value))
                .build();
        return tx.scan(Scan.newBuilder().namespace(namespace).table(table)
                        .indexKey(indexKey).build())
                .stream()
                .map(ValueCodec::toMap)
                .toList();
    }

    public Optional<Map<String, Object>> get(DistributedTransaction tx, String namespace, String table,
                                             Map<String, Object> keyValues) throws TransactionException {
        TableMetadata meta = metadata(namespace, table);
        Key partitionKey = ValueCodec.buildKey(meta,
                new ArrayList<>(meta.getPartitionKeyNames()), keyValues);
        Get get = meta.getClusteringKeyNames().isEmpty()
                ? Get.newBuilder().namespace(namespace).table(table)
                        .partitionKey(partitionKey).build()
                : Get.newBuilder().namespace(namespace).table(table)
                        .partitionKey(partitionKey)
                        .clusteringKey(ValueCodec.buildKey(meta,
                                new ArrayList<>(meta.getClusteringKeyNames()), keyValues))
                        .build();
        return tx.get(get).map(ValueCodec::toMap);
    }

    // ---- writes ----------------------------------------------------------

    /** Inserts a row; {@code values} must contain all key columns. */
    public void insert(DistributedTransaction tx, String namespace, String table,
                       Map<String, Object> values) throws TransactionException {
        TableMetadata meta = metadata(namespace, table);
        InsertBuilder.Buildable builder = Insert.newBuilder().namespace(namespace).table(table)
                .partitionKey(ValueCodec.buildKey(meta,
                        new ArrayList<>(meta.getPartitionKeyNames()), values));
        if (!meta.getClusteringKeyNames().isEmpty()) {
            builder.clusteringKey(ValueCodec.buildKey(meta,
                    new ArrayList<>(meta.getClusteringKeyNames()), values));
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String column = entry.getKey();
            if (meta.getPartitionKeyNames().contains(column)
                    || meta.getClusteringKeyNames().contains(column)) {
                continue;
            }
            if (!meta.getColumnNames().contains(column)) {
                throw new CustomException("Column '" + column + "' does not exist in "
                        + namespace + "." + table, 400);
            }
            builder.value(ValueCodec.toColumn(column, meta.getColumnDataType(column), entry.getValue()));
        }
        tx.insert(builder.build());
    }

    /** Updates non-key columns of an existing row (aborts when the row is gone). */
    public void update(DistributedTransaction tx, String namespace, String table,
                       Map<String, Object> keyValues, Map<String, Object> changes)
            throws TransactionException {
        TableMetadata meta = metadata(namespace, table);
        UpdateBuilder.Buildable builder = Update.newBuilder().namespace(namespace).table(table)
                .partitionKey(ValueCodec.buildKey(meta,
                        new ArrayList<>(meta.getPartitionKeyNames()), keyValues));
        if (!meta.getClusteringKeyNames().isEmpty()) {
            builder.clusteringKey(ValueCodec.buildKey(meta,
                    new ArrayList<>(meta.getClusteringKeyNames()), keyValues));
        }
        for (Map.Entry<String, Object> entry : changes.entrySet()) {
            String column = entry.getKey();
            if (!meta.getColumnNames().contains(column)) {
                throw new CustomException("Column '" + column + "' does not exist in "
                        + namespace + "." + table, 400);
            }
            builder.value(ValueCodec.toColumn(column, meta.getColumnDataType(column), entry.getValue()));
        }
        tx.update(builder.build());
    }

    public void delete(DistributedTransaction tx, String namespace, String table,
                       Map<String, Object> keyValues) throws TransactionException {
        TableMetadata meta = metadata(namespace, table);
        Key partitionKey = ValueCodec.buildKey(meta,
                new ArrayList<>(meta.getPartitionKeyNames()), keyValues);
        Delete delete = meta.getClusteringKeyNames().isEmpty()
                ? Delete.newBuilder().namespace(namespace).table(table)
                        .partitionKey(partitionKey).build()
                : Delete.newBuilder().namespace(namespace).table(table)
                        .partitionKey(partitionKey)
                        .clusteringKey(ValueCodec.buildKey(meta,
                                new ArrayList<>(meta.getClusteringKeyNames()), keyValues))
                        .build();
        tx.delete(delete);
    }

    // ---- DDL (cache-invalidating wrappers) --------------------------------

    public void createNamespace(String namespace) {
        try {
            admin.createNamespace(namespace, true);
        } catch (ExecutionException e) {
            throw new CustomException("Failed to create namespace " + namespace + ": "
                    + e.getMessage(), e, 500);
        }
    }

    public void createTable(String namespace, String table, TableMetadata desired) {
        try {
            admin.createTable(namespace, table, desired, true);
        } catch (ExecutionException e) {
            throw new CustomException("Failed to create table " + namespace + "." + table + ": "
                    + e.getMessage(), e, 500);
        }
        invalidateMetadata(namespace, table);
    }

    /** Creates the table, replacing an existing one whose metadata differs. */
    public void createOrReplaceTable(String namespace, String table, TableMetadata desired) {
        TableMetadata existing = metadataOrNull(namespace, table);
        try {
            if (existing != null && !existing.equals(desired)) {
                admin.dropTable(namespace, table);
                existing = null;
            }
            if (existing == null) {
                admin.createTable(namespace, table, desired, true);
            }
        } catch (ExecutionException e) {
            throw new CustomException("Failed to (re)create table " + namespace + "." + table + ": "
                    + e.getMessage(), e, 500);
        }
        invalidateMetadata(namespace, table);
    }

    public void dropTable(String namespace, String table) {
        try {
            admin.dropTable(namespace, table, true);
        } catch (ExecutionException e) {
            throw new CustomException("Failed to drop table " + namespace + "." + table + ": "
                    + e.getMessage(), e, 500);
        }
        invalidateMetadata(namespace, table);
    }

    public void truncateTable(String namespace, String table) {
        try {
            admin.truncateTable(namespace, table);
        } catch (ExecutionException e) {
            throw new CustomException("Failed to truncate table " + namespace + "." + table + ": "
                    + e.getMessage(), e, 500);
        }
    }
}
