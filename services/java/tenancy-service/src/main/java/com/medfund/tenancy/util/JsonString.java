package com.medfund.tenancy.util;

/**
 * Thin wrapper around a JSON string value.
 * Used as a distinct type so R2DBC custom converters can map it to/from
 * PostgreSQL's {@code jsonb} type without ambiguity with plain {@code String} columns.
 */
public record JsonString(String value) {

    public static JsonString of(String value) {
        return new JsonString(value != null ? value : "{}");
    }

    public static JsonString empty() {
        return new JsonString("{}");
    }

    @Override
    public String toString() {
        return value;
    }
}
