package com.newtonite.ops.comment;

import com.newtonite.ops.activity.ActivityService;
import com.newtonite.ops.common.ApiException;
import com.newtonite.ops.common.Cursor;
import com.newtonite.ops.common.PageResponse;
import com.newtonite.ops.identity.CurrentUser;
import com.newtonite.ops.identity.TeamRole;
import com.newtonite.ops.item.WorkItemService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static com.newtonite.ops.common.Json.obj;

/**
 * Comments are append-only. Adding one does not bump the item's version, so discussion never makes
 * someone else's pending edit "stale".
 */
@Service
public class CommentService {

    private final CommentRepository comments;
    private final EntityManager em;
    private final WorkItemService items;
    private final ActivityService activity;

    public CommentService(CommentRepository comments, EntityManager em, WorkItemService items,
                          ActivityService activity) {
        this.comments = comments;
        this.em = em;
        this.items = items;
        this.activity = activity;
    }

    @Transactional
    public CommentEntry add(CurrentUser user, long itemId, String body) {
        WorkItemService.Loaded loaded = items.loadVisible(user, itemId);
        if (!loaded.role().atLeast(TeamRole.MEMBER)) {
            throw ApiException.forbidden("A " + loaded.role() + " of this team cannot comment");
        }
        String text = body.trim();
        Comment comment = comments.save(new Comment(itemId, user.id(), text));
        activity.record(itemId, user.id(), "COMMENTED",
                obj("commentId", comment.getId(), "excerpt", text.length() > 80 ? text.substring(0, 80) + "..." : text));
        return new CommentEntry(comment.getId(), text, user.id(), user.displayName(), comment.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public PageResponse<CommentEntry> list(CurrentUser user, long itemId, String cursorValue, int limit) {
        items.loadVisible(user, itemId);
        Cursor cursor = Cursor.decode(cursorValue);
        String cursorClause = cursor == null ? "" : "AND (c.createdAt, c.id) < (:cAt, :cId)";
        TypedQuery<Tuple> query = em.createQuery("""
                SELECT c.id AS id, c.body AS body, c.authorId AS authorId, u.displayName AS authorName,
                       c.createdAt AS createdAt
                FROM Comment c JOIN User u ON u.id = c.authorId
                WHERE c.itemId = :itemId %s
                ORDER BY c.createdAt DESC, c.id DESC
                """.formatted(cursorClause), Tuple.class)
                .setParameter("itemId", itemId)
                .setMaxResults(limit + 1);
        if (cursor != null) {
            query.setParameter("cAt", cursor.at()).setParameter("cId", cursor.id());
        }
        List<CommentEntry> rows = query.getResultList().stream().map(t -> new CommentEntry(
                t.get("id", Long.class), t.get("body", String.class), t.get("authorId", Long.class),
                t.get("authorName", String.class), t.get("createdAt", Instant.class))).toList();

        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            CommentEntry last = rows.get(limit - 1);
            next = Cursor.encode(last.createdAt(), last.id());
        }
        return new PageResponse<>(rows, next);
    }

    public record CommentEntry(long id, String body, long authorId, String authorName, Instant createdAt) {
    }
}
