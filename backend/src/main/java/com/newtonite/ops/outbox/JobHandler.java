package com.newtonite.ops.outbox;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Processes one type of outbox job. Runs inside the worker's transaction, so the handler's own
 * database writes commit together with the job being marked DONE.
 *
 * <p>Handlers MUST be idempotent: delivery is at-least-once (a job can run again if the worker
 * crashes after the handler's side effects but before commit, or if an external call succeeded
 * but the commit failed).
 */
public interface JobHandler {

    String type();

    void handle(long jobId, JsonNode payload);
}
