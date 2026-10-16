package com.newtonite.ops.item;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Request and response shapes of the work-item API. */
public final class ItemDtos {

    private ItemDtos() {
    }

    public record UserRef(long id, String name) {
    }

    /** Row in a list. Deliberately excludes the description to keep pages small. */
    public record WorkItemSummary(long id, long teamId, String teamName, String title, ItemType type,
                                  Priority priority, Status status, UserRef assignee, Instant dueAt,
                                  boolean overdue, long version, Instant createdAt, Instant updatedAt) {
    }

    public record WorkItemDetail(long id, long teamId, String teamName, String title, String description,
                                 ItemType type, Priority priority, Status status, UserRef assignee,
                                 UserRef createdBy, Instant dueAt, boolean overdue, long version,
                                 Instant createdAt, Instant updatedAt, Permissions permissions) {
    }

    /**
     * What the current user may do with this item, computed by the same server rules that enforce it.
     * The UI uses it to show buttons; the server still checks every request.
     */
    public record Permissions(boolean canEdit, boolean canClaim, boolean canAssign, boolean canComment,
                              List<TransitionOption> transitions) {
    }

    public record TransitionOption(Status to, String action, boolean noteRequired) {
    }

    public record CreateItemRequest(
            @NotNull Long teamId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 10_000) String description,
            @NotNull ItemType type,
            @NotNull Priority priority,
            Instant dueAt) {
    }

    /** Partial update: null fields are left unchanged. {@code version} is the version the user was looking at. */
    public record UpdateItemRequest(
            @NotNull Long version,
            @Size(max = 200) String title,
            @Size(max = 10_000) String description,
            ItemType type,
            Priority priority,
            Instant dueAt,
            Boolean clearDueAt) {
    }

    /** {@code assigneeId} null means unassign. */
    public record AssignRequest(Long assigneeId, @NotNull Long version) {
    }

    public record TransitionRequest(@NotNull Status to, @NotNull Long version, @Size(max = 2_000) String note) {
    }

    /**
     * List filters, bound from query parameters. Lists accept repeated or comma-separated values,
     * e.g. {@code ?status=OPEN,IN_PROGRESS}. {@code assignee} is "me", "none" or a user id.
     */
    public record ItemFilter(String view, Long teamId, List<Status> status, List<Priority> priority,
                             List<ItemType> type, String assignee, @Size(max = 200) String q, String cursor,
                             @Min(1) @Max(100) Integer limit) {
    }

    public record DashboardSummary(long mine, long unassignedUrgent, long awaitingMyApproval, long overdue) {
    }
}
