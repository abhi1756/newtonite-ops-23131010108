package com.newtonite.ops.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    /**
     * Claims a key: returns 1 if this request inserted it, 0 if it already existed.
     *
     * <p>This is the one native query in the project. JPA has no "insert if absent": persisting a duplicate
     * raises a constraint violation, which aborts the whole PostgreSQL transaction, so the stored response
     * could not even be read afterwards. {@code ON CONFLICT DO NOTHING} also makes a concurrent duplicate
     * wait until the first request commits, then report 0.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys (user_id, idem_key, request_hash)
            VALUES (:userId, :key, :hash)
            ON CONFLICT (user_id, idem_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("key") String key, @Param("hash") String hash);

    Optional<IdempotencyKey> findByUserIdAndKey(Long userId, String key);

    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
