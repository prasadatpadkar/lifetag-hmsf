package com.lifetag.hms.controller;

import com.lifetag.hms.entity.Notification;
import com.lifetag.hms.repository.NotificationRepository;
import com.lifetag.hms.security.UserPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Every endpoint here scopes to the signed-in account (from the JWT) — there is no
 *  "list notifications for any user id" endpoint, so one account can never read
 *  another's notifications. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    @Autowired
    private NotificationRepository notificationRepository;

    @GetMapping
    public List<Notification> getMine(@AuthenticationPrincipal UserPrincipal principal) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(principal.getUser().getId());
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal UserPrincipal principal) {
        long count = notificationRepository.countByUserIdAndReadFalse(principal.getUser().getId());
        return Map.of("count", count);
    }

    @PutMapping("/{id}/read")
    @Transactional
    public ResponseEntity<?> markRead(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        Notification n = notificationRepository.findById(id).orElse(null);
        if (n == null) {
            return ResponseEntity.notFound().build();
        }
        if (!n.getUser().getId().equals(principal.getUser().getId())) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Not your notification"));
        }
        n.setRead(true);
        return ResponseEntity.ok(notificationRepository.save(n));
    }

    @PutMapping("/read-all")
    @Transactional
    public ResponseEntity<?> markAllRead(@AuthenticationPrincipal UserPrincipal principal) {
        List<Notification> unread = notificationRepository.findByUserIdAndReadFalseOrderByCreatedAtDesc(principal.getUser().getId());
        unread.forEach(n -> n.setRead(true));
        notificationRepository.saveAll(unread);
        return ResponseEntity.ok(Map.of("success", true, "updated", unread.size()));
    }

    /** Dismiss (cancel) a single notification. */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        Notification n = notificationRepository.findById(id).orElse(null);
        if (n == null) {
            return ResponseEntity.notFound().build();
        }
        if (!n.getUser().getId().equals(principal.getUser().getId())) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Not your notification"));
        }
        notificationRepository.delete(n);
        return ResponseEntity.noContent().build();
    }

    /** Dismiss (cancel) every notification belonging to the signed-in account. */
    @DeleteMapping
    @Transactional
    public ResponseEntity<?> deleteAll(@AuthenticationPrincipal UserPrincipal principal) {
        List<Notification> mine = notificationRepository.findByUserIdOrderByCreatedAtDesc(principal.getUser().getId());
        notificationRepository.deleteAll(mine);
        return ResponseEntity.ok(Map.of("success", true, "deleted", mine.size()));
    }
}
