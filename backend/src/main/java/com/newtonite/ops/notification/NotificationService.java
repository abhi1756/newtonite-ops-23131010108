package com.newtonite.ops.notification;

import com.newtonite.ops.common.ApiException;
import com.newtonite.ops.identity.CurrentUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** The caller's own notifications. Users can only ever see and mark their own. */
@Service
public class NotificationService {

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public NotificationPage list(CurrentUser user, int limit) {
        List<NotificationEntry> items = notifications
                .findByUserIdOrderByCreatedAtDescIdDesc(user.id(), PageRequest.of(0, limit)).stream()
                .map(n -> new NotificationEntry(n.getId(), n.getItemId(), n.getMessage(), n.isRead(), n.getCreatedAt()))
                .toList();
        return new NotificationPage(items, notifications.countByUserIdAndReadAtIsNull(user.id()));
    }

    @Transactional
    public void markRead(CurrentUser user, long id) {
        notifications.findByIdAndUserId(id, user.id())
                .orElseThrow(() -> ApiException.notFound("Notification " + id))
                .markRead();
    }

    @Transactional
    public void markAllRead(CurrentUser user) {
        notifications.markAllRead(user.id(), Instant.now());
    }

    public record NotificationEntry(long id, Long itemId, String message, boolean read, Instant createdAt) {
    }

    public record NotificationPage(List<NotificationEntry> items, long unreadCount) {
    }
}
