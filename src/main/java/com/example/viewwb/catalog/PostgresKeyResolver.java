package com.example.viewwb.catalog;

import org.springframework.stereotype.Component;

/**
 * PostgreSQL データソースの PK を information_schema から取得する {@link KeyResolver}。
 * schema = カタログ名前空間の displayName(PG のスキーマ名)。
 */
@Component
public class PostgresKeyResolver extends JdbcKeyResolver {

    @Override
    public boolean supports(String providerType) {
        if (providerType == null) {
            return false;
        }
        String p = providerType.toLowerCase();
        return p.equals("postgresql") || p.equals("postgres");
    }

    @Override
    protected String jdbcUrl(String host, int port, String database) {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    @Override
    protected String primaryKeySql() {
        return "SELECT kcu.column_name"
                + " FROM information_schema.table_constraints tc"
                + " JOIN information_schema.key_column_usage kcu"
                + "   ON tc.constraint_name = kcu.constraint_name"
                + "  AND tc.table_schema = kcu.table_schema"
                + " WHERE tc.constraint_type = 'PRIMARY KEY'"
                + "   AND tc.table_schema = ?"
                + "   AND tc.table_name = ?"
                + " ORDER BY kcu.ordinal_position";
    }
}
