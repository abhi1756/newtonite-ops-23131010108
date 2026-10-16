package com.newtonite.ops.outbox;

import com.newtonite.ops.common.Json;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Transactional outbox. A job is inserted in the SAME transaction as the business change, so either
 * both are committed or neither is: no "item updated but notification lost", and no notification
 * about a change that was rolled back. A background worker processes the jobs afterwards.
 */
@Service
public class OutboxService {

    public static final String NOTIFY_ASSIGNEE = "NOTIFY_ASSIGNEE";
    public static final String NOTIFY_APPROVERS = "NOTIFY_APPROVERS";
    public static final String NOTIFY_DECISION = "NOTIFY_DECISION";

    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;

    public OutboxService(NamedParameterJdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String type, Map<String, Object> payload) {
        jdbc.update("INSERT INTO outbox_jobs (type, payload) VALUES (:type, CAST(:payload AS jsonb))",
                new MapSqlParameterSource("type", type).addValue("payload", json.write(payload)));
    }
}
