package com.newtonite.ops.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** One entry of an item's append-only history. Never updated or deleted. */
@Entity
@Table(name = "activity_events")
public class ActivityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false, updatable = false)
    private Long itemId;

    /** Null for system actions. */
    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(nullable = false, updatable = false)
    private String type;

    /** Old/new values of the change, stored as JSONB (e.g. {"from": "OPEN", "to": "IN_PROGRESS"}). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private Map<String, Object> data;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ActivityEvent() {
    }

    public ActivityEvent(Long itemId, Long actorId, String type, Map<String, Object> data) {
        this.itemId = itemId;
        this.actorId = actorId;
        this.type = type;
        this.data = data;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getItemId() {
        return itemId;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getType() {
        return type;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
