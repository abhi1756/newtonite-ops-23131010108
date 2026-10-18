package com.newtonite.ops.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * Keyset-pagination cursor: the (timestamp, id) of the last row of a page, encoded as an opaque string.
 * The next page is "rows strictly after this position", so pages stay correct while rows are inserted
 * and cost the same no matter how deep the user scrolls (no OFFSET).
 */
public record Cursor(Instant at, long id) {

    public static String encode(Instant at, long id) {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, at);   // Postgres stores microseconds
        String raw = micros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split(":");
            Instant at = Instant.EPOCH.plus(Long.parseLong(parts[0]), ChronoUnit.MICROS);
            return new Cursor(at, Long.parseLong(parts[1]));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("INVALID_CURSOR", "Cursor is invalid");
        }
    }
}
