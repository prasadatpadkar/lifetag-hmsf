package com.lifetag.hms.service;

import com.lifetag.hms.entity.Notification;
import com.lifetag.hms.entity.Role;
import com.lifetag.hms.entity.User;
import com.lifetag.hms.repository.NotificationRepository;
import com.lifetag.hms.repository.UserRepository;
import org.springframework.stereotype.Service;

/**
 * Central place every other controller goes through to raise an in-app notification.
 * Kept as a thin service (rather than each controller writing to NotificationRepository
 * directly) so the "who gets notified about what" rules live in one file.
 */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    public NotificationService(NotificationRepository notificationRepository, UserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    /** Notify a single account. Safe to call with a null user (no-op) so callers
     *  don't need to null-check optional links (e.g. a patient with no portal login). */
    public void notifyUser(User user, String title, String message, String type, String link) {
        if (user == null) return;
        notificationRepository.save(new Notification(user, title, message, type, link));
    }

    /** Notify every account holding a given role (e.g. every Admin, every Reception desk). */
    public void notifyRole(Role role, String title, String message, String type, String link) {
        for (User user : userRepository.findByRole(role)) {
            notificationRepository.save(new Notification(user, title, message, type, link));
        }
    }
}
