package com.newtonite.ops.comment;

import com.newtonite.ops.comment.CommentService.CommentEntry;
import com.newtonite.ops.common.PageResponse;
import com.newtonite.ops.idempotency.IdempotencyService;
import com.newtonite.ops.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/items/{itemId}/comments")
public class CommentController {

    private final CommentService comments;
    private final IdempotencyService idempotency;

    public CommentController(CommentService comments, IdempotencyService idempotency) {
        this.comments = comments;
        this.idempotency = idempotency;
    }

    @GetMapping
    public PageResponse<CommentEntry> list(CurrentUser user, @PathVariable long itemId,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return comments.list(user, itemId, cursor, limit);
    }

    @PostMapping
    public ResponseEntity<?> add(CurrentUser user, @PathVariable long itemId,
                                 @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
                                 @Valid @RequestBody CommentRequest request) {
        return idempotency.execute(user, key, "POST /api/items/" + itemId + "/comments", request,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(comments.add(user, itemId, request.body())));
    }

    public record CommentRequest(@NotBlank @Size(max = 5_000) String body) {
    }
}
