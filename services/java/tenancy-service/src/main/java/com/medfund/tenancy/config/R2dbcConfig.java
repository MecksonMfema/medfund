package com.medfund.tenancy.config;

import com.medfund.tenancy.util.JsonString;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

import java.util.List;

@Configuration
@EnableR2dbcRepositories(basePackages = {
        "com.medfund.tenancy.repository",
        "com.medfund.shared.scheduler",
        // NotificationRepository — required wherever JobEventPublisher loads.
        "com.medfund.shared.notification",
})
@EnableR2dbcAuditing
public class R2dbcConfig {

    /**
     * Registers converters so Spring Data R2DBC can map PostgreSQL {@code jsonb}
     * columns to/from {@link JsonString} without touching plain {@code String} columns.
     */
    @Bean
    public R2dbcCustomConversions r2dbcCustomConversions() {
        return R2dbcCustomConversions.of(PostgresDialect.INSTANCE, List.of(
                new JsonToJsonStringConverter(),
                new JsonStringToJsonConverter()
        ));
    }

    @ReadingConverter
    static class JsonToJsonStringConverter implements Converter<Json, JsonString> {
        @Override
        public JsonString convert(Json source) {
            return JsonString.of(source.asString());
        }
    }

    @WritingConverter
    static class JsonStringToJsonConverter implements Converter<JsonString, Json> {
        @Override
        public Json convert(JsonString source) {
            return Json.of(source.value());
        }
    }
}
