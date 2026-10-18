package com.newtonite.ops.notification;

import com.newtonite.ops.identity.CurrentUser;
import com.newtonite.ops.notification.NotificationService.NotificationPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public NotificationPage list(CurrentUser user, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return notifications.list(user, limit);
    }

    @PostMapping("/{id}/read")
    public void markRead(CurrentUser user, @PathVariable long id) {
        notifications.markRead(user, id);
    }

    @PostMapping("/read-all")
    public void markAllRead(CurrentUser user) {
        notifications.markAllRead(user);
    }
}
