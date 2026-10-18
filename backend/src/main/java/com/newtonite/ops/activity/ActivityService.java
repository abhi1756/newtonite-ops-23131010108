package com.newtonite.ops.activity;

import com.newtonite.ops.common.Cursor;
import com.newtonite.ops.common.PageResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Append-only history. {@link #record} must run inside the caller's transaction
 * (MANDATORY), so an event can never be written without the change it describes, or vice versa.
 */
@Service
public class ActivityService {

    private final ActivityEventRepository events;
    private final EntityManager em;

    public ActivityService(ActivityEventRepository events, EntityManager em) {
        this.events = events;
        this.em = em;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long itemId, Long actorId, String type, Map<String, Object> data) {
        events.save(new ActivityEvent(itemId, actorId, type, data));
    }

    /** Newest first, keyset-paginated. Caller must have checked the user may see the item. */
    @Transactional(readOnly = true)
    public PageResponse<ActivityEntry> list(long itemId, String cursorValue, int limit) {
        Cursor cursor = Cursor.decode(cursorValue);
        String cursorClause = cursor == null ? "" : "AND (e.createdAt, e.id) < (:cAt, :cId)";
        TypedQuery<Tuple> query = em.createQuery("""
                SELECT e AS event, u.displayName AS actorName
                FROM ActivityEvent e LEFT JOIN User u ON u.id = e.actorId
                WHERE e.itemId = :itemId %s
                ORDER BY e.createdAt DESC, e.id DESC
                """.formatted(cursorClause), Tuple.class)
                .setParameter("itemId", itemId)
                .setMaxResults(limit + 1);   // one extra row tells us whether there is a next page
        if (cursor != null) {
            query.setParameter("cAt", cursor.at()).setParameter("cId", cursor.id());
        }
        List<ActivityEntry> rows = query.getResultList().stream().map(t -> {
            ActivityEvent e = t.get("event", ActivityEvent.class);
            return new ActivityEntry(e.getId(), e.getType(), e.getData(), e.getActorId(),
                    t.get("actorName", String.class), e.getCreatedAt());
        }).toList();

        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            ActivityEntry last = rows.get(limit - 1);
            next = Cursor.encode(last.createdAt(), last.id());
        }
        return new PageResponse<>(rows, next);
    }

    public record ActivityEntry(long id, String type, Map<String, Object> data, Long actorId, String actorName,
                                Instant createdAt) {
    }
}
