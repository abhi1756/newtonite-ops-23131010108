package com.newtonite.ops.outbox;

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

    static final int DEFAULT_MAX_ATTEMPTS = 5;

    private final OutboxJobRepository jobs;

    public OutboxService(OutboxJobRepository jobs) {
        this.jobs = jobs;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String type, Map<String, Object> payload) {
        jobs.save(new OutboxJob(type, payload, DEFAULT_MAX_ATTEMPTS));
    }
}
