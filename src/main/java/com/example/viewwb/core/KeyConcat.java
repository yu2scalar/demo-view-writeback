package com.example.viewwb.core;

import com.example.viewwb.exception.CustomException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * View 実体の内部キー列 <alias>_pk の値を作る(plan-005 T1、設計は
 * docs/design-note-mv-maintenance.md 決定欄)。
 *
 * ソーステーブルのキー列値を「正規文字列化 → 成分ごとに Base64URL(パディングなし)→ '.' 連結」
 * で単一の TEXT に畳む。Base64URL のアルファベットに '.' は含まれないため連結の曖昧さが無く、
 * 出力はバックエンドのキー列禁止文字(Cosmos の ':','/','\\','#','?'、Dynamo の U+0000)とも
 * 衝突しない — エンコード済みカラムをそのまま ScalarDB のキー列・index 列に置ける。
 */
public final class KeyConcat {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private KeyConcat() {
    }

    /** row から keyColumns(定義順)の値を取り出して連結キーを作る。値が無い列は 400 */
    public static String encode(Map<String, Object> row, List<String> keyColumns) {
        StringJoiner joiner = new StringJoiner(".");
        for (String column : keyColumns) {
            Object value = row.get(column);
            if (value == null) {
                throw new CustomException("キー列 '" + column + "' の値がありません(連結キー生成)", 400);
            }
            joiner.add(ENCODER.encodeToString(canonical(value).getBytes(StandardCharsets.UTF_8)));
        }
        return joiner.toString();
    }

    /**
     * 正規文字列化。同じ論理値が型表現の揺れ(Integer/Long 等)で別キーにならないよう、
     * 整数系は long の10進表記に寄せる。
     */
    private static String canonical(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            return String.valueOf(((Number) value).longValue());
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Boolean b) {
            return b.toString();
        }
        throw new CustomException("連結キーに使えない型です: " + value.getClass().getSimpleName()
                + " (" + value + ")", 400);
    }
}
