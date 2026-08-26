package com.example.viewwb.catalog;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 非 ScalarDB データソースの主キー(PK)を、カタログの接続情報(provider_payload_json)から
 * 直接取得するための抽象。プロバイダ種別(postgresql / mysql / ...)ごとに実装を用意し、
 * {@link #supports(String)} で選択する。
 *
 * <p>Analytics カタログは PK 情報を持たないため、ScalarDB テーブルは getTableMetadata、
 * 非 ScalarDB テーブルはこの経路で PK を補完する(plan-010)。将来 Analytics が対応する
 * DB 種別を増やす場合はここに実装を足す。
 */
public interface KeyResolver {

    /** provider_type(小文字化・別名を吸収して判定)を扱えるなら true。 */
    boolean supports(String providerType);

    /**
     * 主キー列を ordinal 順で返す。取得できない(PK 無し・接続失敗など)場合は空リスト。
     * 例外は投げず空リストで返し、呼び出し側の手動フォールバックに委ねる。
     *
     * @param payload data_source の provider_payload_json(host/port/username/password/database を含む)
     * @param schema  スキーマ名(= カタログ名前空間の displayName。PG=schema, MySQL=database)
     * @param table   テーブル名
     */
    List<String> primaryKeyColumns(JsonNode payload, String schema, String table);
}
