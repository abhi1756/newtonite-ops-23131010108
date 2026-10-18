package com.newtonite.ops.idempotency;

import com.newtonite.ops.common.ApiException;
import com.newtonite.ops.common.Json;
import com.newtonite.ops.identity.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Makes mutating requests safe to repeat (double clicks, client retries after a timeout).
 *
 * <p>The key is inserted in the SAME transaction as the action, and the response is stored before commit:
 * <ul>
 *   <li>First request: key inserted, action runs, response saved, all committed together.</li>
 *   <li>Repeat after success: the stored response is returned; the action does not run again.</li>
 *   <li>Concurrent repeat: its INSERT waits on the unique index until the first commits, then replays.</li>
 *   <li>First request failed: everything rolled back, including the key, so a retry runs normally.</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyKeyRepository keys;
    private final Json json;
    private final Duration retention;

    public IdempotencyService(IdempotencyKeyRepository keys, Json json,
                              @Value("${app.idempotency.retention}") Duration retention) {
        this.keys = keys;
        this.json = json;
        this.retention = retention;
    }

    /**
     * @param operation   method and path, e.g. "POST /api/items/5/claim"; part of the request fingerprint
     * @param requestBody body used to detect a key reused for a different request (may be null)
     */
    @Transactional
    public ResponseEntity<?> execute(CurrentUser user, String key, String operation, Object requestBody,
                                     Supplier<ResponseEntity<?>> action) {
        if (key == null) {
            return action.get();
        }
        if (key.isBlank() || key.length() > 100) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", HEADER + " must be 1-100 characters");
        }
        String hash = sha256(operation + "\n" + (requestBody == null ? "" : json.write(requestBody)));

        if (keys.insertIfAbsent(user.id(), key, hash) == 1) {
            ResponseEntity<?> response = action.get();
            IdempotencyKey stored = keys.findByUserIdAndKey(user.id(), key).orElseThrow();
            stored.storeResponse(response.getStatusCode().value(),
                    response.getBody() == null ? null : json.write(response.getBody()));   // saved at commit
            return response;
        }

        IdempotencyKey stored = keys.findByUserIdAndKey(user.id(), key)
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_KEY_IN_USE", "Please retry the request"));
        if (!stored.getRequestHash().equals(hash)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "This " + HEADER + " was already used for a different request");
        }
        if (stored.getResponseStatus() == null) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_IN_USE", "The original request is still being processed");
        }
        log.debug("Replaying stored response for idempotency key {} of user {}", key, user.id());
        return ResponseEntity.status(stored.getResponseStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .header(REPLAYED_HEADER, "true")
                .body(stored.getResponseBody());
    }

    /** Keys only need to outlive realistic retries; old ones are purged so the table stays small. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    @Transactional
    public void purgeExpired() {
        int deleted = keys.deleteCreatedBefore(Instant.now().minus(retention));
        if (deleted > 0) {
            log.info("Purged {} expired idempotency keys", deleted);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
