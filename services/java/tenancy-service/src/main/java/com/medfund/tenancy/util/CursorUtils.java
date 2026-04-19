package com.medfund.tenancy.util;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public final class CursorUtils {

    private CursorUtils() {}

    public static String encode(Instant createdAt, UUID id) {
        String raw = createdAt.toString() + "|" + id.toString();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
    }

    public static String[] decode(String cursor) {
        byte[] bytes = Base64.getUrlDecoder().decode(cursor);
        return new String(bytes).split("\\|", 2);
    }
}
