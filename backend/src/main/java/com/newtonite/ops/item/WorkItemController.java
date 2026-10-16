package com.newtonite.ops.item;

import com.newtonite.ops.activity.ActivityService;
import com.newtonite.ops.activity.ActivityService.ActivityEntry;
import com.newtonite.ops.common.PageResponse;
import com.newtonite.ops.idempotency.IdempotencyService;
import com.newtonite.ops.identity.CurrentUser;
import com.newtonite.ops.item.ItemDtos.AssignRequest;
import com.newtonite.ops.item.ItemDtos.CreateItemRequest;
import com.newtonite.ops.item.ItemDtos.DashboardSummary;
import com.newtonite.ops.item.ItemDtos.ItemFilter;
import com.newtonite.ops.item.ItemDtos.TransitionRequest;
import com.newtonite.ops.item.ItemDtos.UpdateItemRequest;
import com.newtonite.ops.item.ItemDtos.WorkItemDetail;
import com.newtonite.ops.item.ItemDtos.WorkItemSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Work-item API. Every mutating endpoint accepts an optional {@code Idempotency-Key} header;
 * the browser always sends one, so retries and double clicks are answered from the stored response.
 */
@RestController
@RequestMapping("/api/items")
public class WorkItemController {

    private final WorkItemService service;
    private final WorkItemQueries queries;
    private final ActivityService activity;
    private final IdempotencyService idempotency;

    public WorkItemController(WorkItemService service, WorkItemQueries queries, ActivityService activity,
                              IdempotencyService idempotency) {
        this.service = service;
        this.queries = queries;
        this.activity = activity;
        this.idempotency = idempotency;
    }

    @GetMapping
    public PageResponse<WorkItemSummary> list(CurrentUser user, @Valid ItemFilter filter) {
        return queries.list(user, filter);
    }

    @GetMapping("/summary")
    public DashboardSummary summary(CurrentUser user) {
        return queries.summary(user);
    }

    @PostMapping
    public ResponseEntity<?> create(CurrentUser user,
                                    @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                    @Valid @RequestBody CreateItemRequest request) {
        return idempotency.execute(user, key, "POST /api/items", request,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(service.create(user, request)));
    }

    @GetMapping("/{id}")
    public WorkItemDetail get(CurrentUser user, @PathVariable long id) {
        return service.get(user, id);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(CurrentUser user, @PathVariable long id,
                                    @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                    @Valid @RequestBody UpdateItemRequest request) {
        return idempotency.execute(user, key, "PATCH /api/items/" + id, request,
                () -> ResponseEntity.ok(service.update(user, id, request)));
    }

    @PostMapping("/{id}/claim")
    public ResponseEntity<?> claim(CurrentUser user, @PathVariable long id,
                                   @RequestHeader(value = IdempotencyService.HEADER, required = false) String key) {
        return idempotency.execute(user, key, "POST /api/items/" + id + "/claim", null,
                () -> ResponseEntity.ok(service.claim(user, id)));
    }

    @PostMapping("/{id}/assign")
    public ResponseEntity<?> assign(CurrentUser user, @PathVariable long id,
                                    @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                    @Valid @RequestBody AssignRequest request) {
        return idempotency.execute(user, key, "POST /api/items/" + id + "/assign", request,
                () -> ResponseEntity.ok(service.assign(user, id, request)));
    }

    @PostMapping("/{id}/transition")
    public ResponseEntity<?> transition(CurrentUser user, @PathVariable long id,
                                        @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                        @Valid @RequestBody TransitionRequest request) {
        return idempotency.execute(user, key, "POST /api/items/" + id + "/transition", request,
                () -> ResponseEntity.ok(service.transition(user, id, request)));
    }

    @GetMapping("/{id}/activity")
    public PageResponse<ActivityEntry> activity(CurrentUser user, @PathVariable long id,
                                                @RequestParam(required = false) String cursor,
                                                @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        service.loadVisible(user, id);
        return activity.list(id, cursor, limit);
    }
}
