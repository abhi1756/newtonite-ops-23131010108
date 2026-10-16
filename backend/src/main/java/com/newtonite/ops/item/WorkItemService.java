package com.newtonite.ops.item;

import com.newtonite.ops.activity.ActivityService;
import com.newtonite.ops.authz.AuthorizationService;
import com.newtonite.ops.common.ApiException;
import com.newtonite.ops.identity.CurrentUser;
import com.newtonite.ops.identity.TeamRole;
import com.newtonite.ops.identity.UserAccount;
import com.newtonite.ops.identity.UserDirectory;
import com.newtonite.ops.item.ItemDtos.AssignRequest;
import com.newtonite.ops.item.ItemDtos.CreateItemRequest;
import com.newtonite.ops.item.ItemDtos.Permissions;
import com.newtonite.ops.item.ItemDtos.TransitionOption;
import com.newtonite.ops.item.ItemDtos.TransitionRequest;
import com.newtonite.ops.item.ItemDtos.UpdateItemRequest;
import com.newtonite.ops.item.ItemDtos.WorkItemDetail;
import com.newtonite.ops.item.ItemDtos.WorkItemSummary;
import com.newtonite.ops.outbox.OutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.newtonite.ops.common.Json.obj;

/**
 * All state changes of a work item. Each public method is one transaction that contains the change,
 * its activity event and any outbox job, so history and notifications can never disagree with the item.
 */
@Service
public class WorkItemService {

    static final Set<Status> CLAIMABLE = EnumSet.of(Status.OPEN, Status.IN_PROGRESS);

    private final WorkItemRepository items;
    private final WorkItemQueries queries;
    private final AuthorizationService authz;
    private final WorkflowPolicy workflow;
    private final ActivityService activity;
    private final OutboxService outbox;
    private final UserDirectory users;

    public WorkItemService(WorkItemRepository items, WorkItemQueries queries, AuthorizationService authz,
                           WorkflowPolicy workflow, ActivityService activity, OutboxService outbox,
                           UserDirectory users) {
        this.items = items;
        this.queries = queries;
        this.authz = authz;
        this.workflow = workflow;
        this.activity = activity;
        this.outbox = outbox;
        this.users = users;
    }

    /** An item together with the caller's effective role in its team. */
    public record Loaded(WorkItem item, TeamRole role) {
    }

    /** Loads an item the user may see; items of other teams look exactly like missing ones (404). */
    @Transactional(readOnly = true)
    public Loaded loadVisible(CurrentUser user, long id) {
        WorkItem item = items.findById(id).orElseThrow(() -> ApiException.notFound("Work item " + id));
        TeamRole role = authz.requireVisible(user, item.getTeamId(), "Work item " + id);
        return new Loaded(item, role);
    }

    @Transactional(readOnly = true)
    public WorkItemDetail get(CurrentUser user, long id) {
        Loaded loaded = loadVisible(user, id);
        return detail(user, loaded.item(), loaded.role());
    }

    @Transactional
    public WorkItemDetail create(CurrentUser user, CreateItemRequest request) {
        TeamRole role = authz.requireRole(user, request.teamId(), TeamRole.MEMBER, "Team", "create items");
        WorkItem item = items.saveAndFlush(new WorkItem(request.teamId(), request.title().trim(),
                request.description() == null ? "" : request.description().trim(),
                request.type(), request.priority(), user.id(), request.dueAt()));
        activity.record(item.getId(), user.id(), "CREATED",
                obj("title", item.getTitle(), "type", item.getType(), "priority", item.getPriority()));
        return detail(user, item, role);
    }

    @Transactional
    public WorkItemDetail update(CurrentUser user, long id, UpdateItemRequest request) {
        Loaded loaded = loadVisible(user, id);
        WorkItem item = loaded.item();
        authz.requireCanEdit(user, item, loaded.role());
        requireCurrentVersion(user, loaded, request.version());
        if (item.getStatus() == Status.CLOSED) {
            throw ApiException.unprocessable("ITEM_CLOSED", "Closed items cannot be edited");
        }

        Map<String, Object> changes = new LinkedHashMap<>();
        if (request.title() != null) {
            String title = request.title().trim();
            if (title.isEmpty()) {
                throw ApiException.badRequest("VALIDATION_FAILED", "Title must not be blank");
            }
            if (!title.equals(item.getTitle())) {
                changes.put("title", obj("from", item.getTitle(), "to", title));
                item.setTitle(title);
            }
        }
        if (request.description() != null && !request.description().trim().equals(item.getDescription())) {
            changes.put("description", obj("changed", true));   // full text is not copied into history
            item.setDescription(request.description().trim());
        }
        if (request.type() != null && request.type() != item.getType()) {
            if (!CLAIMABLE.contains(item.getStatus())) {
                throw ApiException.unprocessable("TYPE_LOCKED",
                        "The type can only change while the item is OPEN or IN_PROGRESS");
            }
            changes.put("type", obj("from", item.getType(), "to", request.type()));
            item.setType(request.type());
        }
        Instant newDue = Boolean.TRUE.equals(request.clearDueAt()) ? null
                : request.dueAt() != null ? request.dueAt() : item.getDueAt();
        if (!Objects.equals(newDue, item.getDueAt())) {
            changes.put("dueAt", obj("from", item.getDueAt(), "to", newDue));
            item.setDueAt(newDue);
        }
        Priority oldPriority = item.getPriority();
        boolean priorityChanged = request.priority() != null && request.priority() != oldPriority;
        if (priorityChanged) {
            item.setPriority(request.priority());
        }

        if (changes.isEmpty() && !priorityChanged) {
            return detail(user, item, loaded.role());   // nothing to do: no version bump, no history noise
        }
        // Flush now so a concurrent write is detected here (@Version) and the response shows the new version.
        items.flush();
        if (priorityChanged) {
            activity.record(id, user.id(), "PRIORITY_CHANGED", obj("from", oldPriority, "to", item.getPriority()));
        }
        if (!changes.isEmpty()) {
            activity.record(id, user.id(), "UPDATED", obj("changes", changes));
        }
        return detail(user, item, loaded.role());
    }

    /**
     * Take ownership of an unassigned item. Safe under concurrency: the conditional UPDATE lets exactly
     * one of several simultaneous claims succeed; the others get 409 naming the winner.
     * Claiming an item you already own succeeds without changing anything (safe to retry).
     */
    @Transactional
    public WorkItemDetail claim(CurrentUser user, long id) {
        Loaded loaded = loadVisible(user, id);
        WorkItem item = loaded.item();
        if (!loaded.role().atLeast(TeamRole.MEMBER)) {
            throw ApiException.forbidden("A " + loaded.role() + " of this team cannot claim items");
        }
        if (Objects.equals(item.getAssigneeId(), user.id())) {
            return detail(user, item, loaded.role());
        }
        if (item.getAssigneeId() != null) {
            throw alreadyClaimed(item.getAssigneeId());
        }
        if (!CLAIMABLE.contains(item.getStatus())) {
            throw ApiException.unprocessable("NOT_CLAIMABLE", "Items in status " + item.getStatus() + " cannot be claimed");
        }

        int updated = items.claimIfUnassigned(id, user.id(), Instant.now(), CLAIMABLE);
        WorkItem current = items.findById(id).orElseThrow(() -> ApiException.notFound("Work item " + id));
        if (updated == 0) {
            if (Objects.equals(current.getAssigneeId(), user.id())) {
                return detail(user, current, loaded.role());
            }
            if (current.getAssigneeId() != null) {
                throw alreadyClaimed(current.getAssigneeId());
            }
            throw ApiException.conflict("STALE_VERSION", "The item changed while claiming it. Reload and try again.");
        }
        activity.record(id, user.id(), "ASSIGNED", obj("fromUserId", null, "fromName", null,
                "toUserId", user.id(), "toName", user.displayName(), "via", "CLAIM"));
        return detail(user, current, loaded.role());
    }

    /** A Lead assigns, reassigns or unassigns. The new assignee is notified asynchronously. */
    @Transactional
    public WorkItemDetail assign(CurrentUser user, long id, AssignRequest request) {
        Loaded loaded = loadVisible(user, id);
        WorkItem item = loaded.item();
        if (loaded.role() != TeamRole.LEAD) {
            throw ApiException.forbidden("Only a Lead can assign items to other people");
        }
        requireCurrentVersion(user, loaded, request.version());
        if (item.getStatus() == Status.CLOSED) {
            throw ApiException.unprocessable("ITEM_CLOSED", "Closed items cannot be reassigned");
        }
        Long target = request.assigneeId();
        Long previous = item.getAssigneeId();
        if (Objects.equals(target, previous)) {
            return detail(user, item, loaded.role());
        }
        if (target == null && item.getStatus() != Status.OPEN) {
            throw ApiException.unprocessable("UNASSIGN_NOT_ALLOWED",
                    "Only OPEN items can be left without an owner; reassign it instead");
        }
        if (target != null) {
            TeamRole targetRole = users.roleOf(target, item.getTeamId()).orElse(null);
            if (targetRole == null || !targetRole.atLeast(TeamRole.MEMBER)) {
                throw ApiException.unprocessable("INVALID_ASSIGNEE", "The assignee must be a Member or Lead of this team");
            }
        }

        item.setAssigneeId(target);
        items.flush();
        activity.record(id, user.id(), "ASSIGNED", obj("fromUserId", previous, "fromName", nameOf(previous),
                "toUserId", target, "toName", nameOf(target), "via", "ASSIGN"));
        if (target != null && target != user.id()) {
            outbox.enqueue(OutboxService.NOTIFY_ASSIGNEE, obj("itemId", id, "title", item.getTitle(),
                    "userId", target, "actorName", user.displayName()));
        }
        return detail(user, item, loaded.role());
    }

    @Transactional
    public WorkItemDetail transition(CurrentUser user, long id, TransitionRequest request) {
        Loaded loaded = loadVisible(user, id);
        WorkItem item = loaded.item();
        if (!loaded.role().atLeast(TeamRole.MEMBER)) {
            throw ApiException.forbidden("A " + loaded.role() + " of this team cannot change an item's status");
        }
        requireCurrentVersion(user, loaded, request.version());
        WorkflowPolicy.Rule rule = workflow.check(item, request.to(), user.id(), loaded.role(), request.note());

        Status from = item.getStatus();
        item.setStatus(request.to());
        items.flush();
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        activity.record(id, user.id(), "STATUS_CHANGED",
                obj("from", from, "to", request.to(), "action", rule.action(), "note", note));

        if (request.to() == Status.AWAITING_APPROVAL) {
            outbox.enqueue(OutboxService.NOTIFY_APPROVERS, obj("itemId", id, "teamId", item.getTeamId(),
                    "title", item.getTitle(), "actorId", user.id(), "actorName", user.displayName()));
        } else if (from == Status.AWAITING_APPROVAL && item.getAssigneeId() != null) {
            outbox.enqueue(OutboxService.NOTIFY_DECISION, obj("itemId", id, "title", item.getTitle(),
                    "userId", item.getAssigneeId(), "decision", request.to() == Status.RESOLVED ? "APPROVED" : "REJECTED",
                    "note", note, "actorName", user.displayName()));
        }
        return detail(user, item, loaded.role());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The client must have seen the latest version. On mismatch the response carries the current
     * item so the UI can show what changed instead of silently overwriting someone else's work.
     */
    private void requireCurrentVersion(CurrentUser user, Loaded loaded, long clientVersion) {
        WorkItem item = loaded.item();
        if (item.getVersion() != clientVersion) {
            throw ApiException.conflict("STALE_VERSION",
                    "This item was changed by someone else since you loaded it",
                    Map.of("current", detail(user, item, loaded.role())));
        }
    }

    private ApiException alreadyClaimed(long assigneeId) {
        String name = nameOf(assigneeId);
        return ApiException.conflict("ALREADY_CLAIMED", "Already assigned to " + name,
                Map.of("assignee", new ItemDtos.UserRef(assigneeId, name)));
    }

    private String nameOf(Long userId) {
        return userId == null ? null : users.findById(userId).map(UserAccount::displayName).orElse("unknown user");
    }

    private WorkItemDetail detail(CurrentUser user, WorkItem item, TeamRole role) {
        WorkItemQueries.DetailRow row = queries.detail(item.getId())
                .orElseThrow(() -> ApiException.notFound("Work item " + item.getId()));
        WorkItemSummary s = row.summary();
        return new WorkItemDetail(s.id(), s.teamId(), s.teamName(), s.title(), row.description(), s.type(),
                s.priority(), s.status(), s.assignee(), row.createdBy(), s.dueAt(), s.overdue(), s.version(),
                s.createdAt(), s.updatedAt(), permissions(user, item, role));
    }

    private Permissions permissions(CurrentUser user, WorkItem item, TeamRole role) {
        boolean member = role.atLeast(TeamRole.MEMBER);
        boolean closed = item.getStatus() == Status.CLOSED;
        List<TransitionOption> transitions = workflow.available(item, user.id(), role).stream()
                .map(r -> new TransitionOption(r.to(), r.action(),
                        r.from() == Status.AWAITING_APPROVAL && r.to() == Status.IN_PROGRESS))
                .toList();
        return new Permissions(
                !closed && authz.canEdit(user, item, role),
                member && item.getAssigneeId() == null && CLAIMABLE.contains(item.getStatus()),
                role == TeamRole.LEAD && !closed,
                member,
                transitions);
    }
}
