package com.example.viewwb.core;

import com.example.viewwb.exception.CustomException;
import com.scalar.db.api.Result;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.BigIntColumn;
import com.scalar.db.io.BlobColumn;
import com.scalar.db.io.BooleanColumn;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.DateColumn;
import com.scalar.db.io.DoubleColumn;
import com.scalar.db.io.FloatColumn;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.Key;
import com.scalar.db.io.TextColumn;
import com.scalar.db.io.TimeColumn;
import com.scalar.db.io.TimestampColumn;
import com.scalar.db.io.TimestampTZColumn;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single place for converting between raw values (JSON request bodies /
 * spark-connect result sets: String / Number / Boolean / java.sql.* /
 * java.time.* / byte[]) and typed ScalarDB columns. Every dynamic read/write
 * goes through here so the supported type set (all 11 ScalarDB types, per
 * docs/design-notes-20260718.md の型マッピング表) is enforced consistently.
 */
public final class ValueCodec {

    private ValueCodec() {
    }

    /** Parses a raw value into the Java type matching {@code type}. */
    public static Object parse(String column, DataType type, Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return switch (type) {
                case BOOLEAN -> raw instanceof Boolean b ? b : Boolean.parseBoolean(text(raw));
                case INT -> toIntChecked(column, raw);
                case BIGINT -> raw instanceof Number n ? n.longValue() : Long.parseLong(text(raw));
                case FLOAT -> raw instanceof Number n ? n.floatValue() : Float.parseFloat(text(raw));
                case DOUBLE -> raw instanceof Number n ? n.doubleValue() : Double.parseDouble(text(raw));
                case TEXT -> String.valueOf(raw);
                case BLOB -> toBytes(raw);
                case DATE -> toDate(raw);
                case TIME -> toTime(raw);
                case TIMESTAMP -> toTimestamp(raw);
                case TIMESTAMPTZ -> toInstantValue(raw);
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new CustomException(
                    "列 '" + column + "' の " + type + " 値が不正です: '" + raw + "'", 400);
        }
    }

    private static String text(Object raw) {
        return String.valueOf(raw).trim();
    }

    /**
     * INT はサイレントに 32bit へ折り返さない。フロー式は long で計算されるため、
     * 範囲超過をここで検出しないと在庫が負値になる等のデータ破壊が起きる
     * (2026-07-18 実障害: 2147483638 + 900 → -2147482758)。
     */
    private static int toIntChecked(String column, Object raw) {
        long v = raw instanceof Number n ? n.longValue() : Long.parseLong(text(raw));
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new CustomException(
                    "列 '" + column + "' の値が INT の範囲を超えています: " + v, 422);
        }
        return (int) v;
    }

    /** Spark: DateType -> java.sql.Date */
    private static LocalDate toDate(Object raw) {
        if (raw instanceof LocalDate d) {
            return d;
        }
        if (raw instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return LocalDate.parse(text(raw));
    }

    /** Spark: TIME(6) は TimestampNTZType(LocalDateTime)で来る -> toLocalTime() */
    private static LocalTime toTime(Object raw) {
        if (raw instanceof LocalTime t) {
            return t;
        }
        if (raw instanceof LocalDateTime dt) {
            return dt.toLocalTime();
        }
        return LocalTime.parse(text(raw));
    }

    /** JSON 応答(JacksonConfig)が付ける RFC3339 オフセットの解決に使うアプリタイムゾーン */
    private static final java.time.ZoneId APP_ZONE = java.time.ZoneId.of("Asia/Tokyo");

    /** Spark: TimestampNTZType -> LocalDateTime */
    private static LocalDateTime toTimestamp(Object raw) {
        if (raw instanceof LocalDateTime dt) {
            return dt;
        }
        if (raw instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        String text = text(raw);
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException e) {
            // GUI は API 応答(RFC3339、"+09:00" 付き)の値をそのまま送り返してくるため、
            // オフセット付きはアプリタイムゾーンへ変換してからオフセットを落とす
            // (JacksonConfig の FlexibleLocalDateTimeDeserializer と同じ規則)
            return OffsetDateTime.parse(text).atZoneSameInstant(APP_ZONE).toLocalDateTime();
        }
    }

    /** Spark: TimestampType -> java.sql.Timestamp -> toInstant() */
    private static Instant toInstantValue(Object raw) {
        if (raw instanceof Instant i) {
            return i;
        }
        if (raw instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        return parseInstant(text(raw));
    }

    private static byte[] toBytes(Object raw) {
        if (raw instanceof byte[] b) {
            return b;
        }
        if (raw instanceof java.nio.ByteBuffer buf) {
            byte[] b = new byte[buf.remaining()];
            buf.duplicate().get(b);
            return b;
        }
        return Base64.getDecoder().decode(text(raw));
    }

    private static Instant parseInstant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return OffsetDateTime.parse(text).toInstant();
        }
    }

    /** Builds a typed ScalarDB column from a raw value. */
    public static Column<?> toColumn(String column, DataType type, Object raw) {
        Object value = parse(column, type, raw);
        if (value == null) {
            return switch (type) {
                case BOOLEAN -> BooleanColumn.ofNull(column);
                case INT -> IntColumn.ofNull(column);
                case BIGINT -> BigIntColumn.ofNull(column);
                case FLOAT -> FloatColumn.ofNull(column);
                case DOUBLE -> DoubleColumn.ofNull(column);
                case TEXT -> TextColumn.ofNull(column);
                case BLOB -> BlobColumn.ofNull(column);
                case DATE -> DateColumn.ofNull(column);
                case TIME -> TimeColumn.ofNull(column);
                case TIMESTAMP -> TimestampColumn.ofNull(column);
                case TIMESTAMPTZ -> TimestampTZColumn.ofNull(column);
            };
        }
        return switch (type) {
            case BOOLEAN -> BooleanColumn.of(column, (Boolean) value);
            case INT -> IntColumn.of(column, (Integer) value);
            case BIGINT -> BigIntColumn.of(column, (Long) value);
            case FLOAT -> FloatColumn.of(column, (Float) value);
            case DOUBLE -> DoubleColumn.of(column, (Double) value);
            case TEXT -> TextColumn.of(column, (String) value);
            case BLOB -> BlobColumn.of(column, (byte[]) value);
            case DATE -> DateColumn.of(column, (LocalDate) value);
            case TIME -> TimeColumn.of(column, (LocalTime) value);
            case TIMESTAMP -> TimestampColumn.of(column, (LocalDateTime) value);
            case TIMESTAMPTZ -> TimestampTZColumn.of(column, (Instant) value);
        };
    }

    /** Builds a Key from the given columns, typed per table metadata. */
    public static Key buildKey(TableMetadata metadata, List<String> columns, Map<String, Object> values) {
        Key.Builder builder = Key.newBuilder();
        for (String column : columns) {
            Object raw = values.get(column);
            if (raw == null) {
                throw new CustomException("Key column '" + column + "' is missing", 400);
            }
            DataType type = metadata.getColumnDataType(column);
            Object value = parse(column, type, raw);
            switch (type) {
                case BOOLEAN -> builder.addBoolean(column, (Boolean) value);
                case INT -> builder.addInt(column, (Integer) value);
                case BIGINT -> builder.addBigInt(column, (Long) value);
                case FLOAT -> builder.addFloat(column, (Float) value);
                case DOUBLE -> builder.addDouble(column, (Double) value);
                case TEXT -> builder.addText(column, (String) value);
                case DATE -> builder.addDate(column, (LocalDate) value);
                case TIME -> builder.addTime(column, (LocalTime) value);
                case TIMESTAMP -> builder.addTimestamp(column, (LocalDateTime) value);
                case TIMESTAMPTZ -> builder.addTimestampTZ(column, (Instant) value);
                default -> throw new CustomException(
                        "Key column '" + column + "': type " + type + " is not supported", 400);
            }
        }
        return builder.build();
    }

    /** Flattens a ScalarDB result into an insertion-ordered column→value map. */
    public static Map<String, Object> toMap(Result result) {
        Map<String, Object> map = new LinkedHashMap<>();
        result.getColumns().forEach((name, column) -> map.put(name, column.getValueAsObject()));
        return map;
    }
}
