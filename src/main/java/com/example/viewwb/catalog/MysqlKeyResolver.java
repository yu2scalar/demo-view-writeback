package com.example.viewwb.catalog;

import org.springframework.stereotype.Component;

/**
 * MySQL データソースの PK を information_schema から取得する {@link KeyResolver}。
 * MySQL は schema = database なので TABLE_SCHEMA にカタログ名前空間(= database)を渡す。
 */
@Component
public class MysqlKeyResolver extends JdbcKeyResolver {

    @Override
    public boolean supports(String providerType) {
        return providerType != null && providerType.toLowerCase().equals("mysql");
    }

    @Override
    protected String jdbcUrl(String host, int port, String database) {
        return "jdbc:mysql://" + host + ":" + port + "/" + database;
    }

    @Override
    protected String primaryKeySql() {
        return "SELECT k.COLUMN_NAME"
                + " FROM information_schema.TABLE_CONSTRAINTS t"
                + " JOIN information_schema.KEY_COLUMN_USAGE k"
                + "   ON t.CONSTRAINT_NAME = k.CONSTRAINT_NAME"
                + "  AND t.TABLE_SCHEMA = k.TABLE_SCHEMA"
                + "  AND t.TABLE_NAME = k.TABLE_NAME"
                + " WHERE t.CONSTRAINT_TYPE = 'PRIMARY KEY'"
                + "   AND t.TABLE_SCHEMA = ?"
                + "   AND t.TABLE_NAME = ?"
                + " ORDER BY k.ORDINAL_POSITION";
    }
}
