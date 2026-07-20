package com.example.viewwb.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Serializes LocalDateTime with the application timezone offset so that
 * responses conform to RFC 3339 (OpenAPI "date-time" format), while the
 * database keeps storing zone-less local timestamps (ScalarDB TIMESTAMP).
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter OFFSET_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeOffsetCustomizer(
            @Value("${app.timezone:Asia/Tokyo}") String timezone) {
        ZoneId zone = ZoneId.of(timezone);
        return builder -> builder
                .serializerByType(LocalDateTime.class, new LocalDateTimeWithOffsetSerializer(zone))
                .deserializerByType(LocalDateTime.class, new FlexibleLocalDateTimeDeserializer(zone));
    }

    static class LocalDateTimeWithOffsetSerializer extends JsonSerializer<LocalDateTime> {
        private final ZoneId zone;

        LocalDateTimeWithOffsetSerializer(ZoneId zone) {
            this.zone = zone;
        }

        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(value.atZone(zone).toOffsetDateTime().format(OFFSET_FORMATTER));
        }
    }

    static class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        private final ZoneId zone;

        FlexibleLocalDateTimeDeserializer(ZoneId zone) {
            this.zone = zone;
        }

        @Override
        public LocalDateTime deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            String text = parser.getValueAsString();
            try {
                // Offset-carrying input (e.g. "...+09:00" or "...Z") is converted
                // to the application timezone before dropping the offset.
                return OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDateTime();
            } catch (DateTimeParseException e) {
                return LocalDateTime.parse(text);
            }
        }
    }
}
