package com.lifetag.hms.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifetag.hms.security.UserPrincipal;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.Table;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.internal.SessionFactoryImpl;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes an audit_log row automatically whenever Hibernate inserts, updates or deletes
 * an entity. The row is inserted with a parameterized JdbcTemplate statement on the
 * same transaction as the change itself, so a rolled-back change leaves no audit row
 * and a committed change always has one.
 *
 * Not audited: the audit table itself, the activity feed and notifications (noise).
 * Never logged: any property whose name looks like a password/secret/token.
 */
@Component
public class AuditEventListener implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);
    private static final Set<String> SKIPPED_ENTITIES = Set.of("AuditLog", "StaffActivity", "Notification");

    private final EntityManagerFactory emf;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditEventListener(EntityManagerFactory emf, JdbcTemplate jdbc) {
        this.emf = emf;
        this.jdbc = jdbc;
    }

    @PostConstruct
    void register() {
        EventListenerRegistry registry = emf.unwrap(SessionFactoryImpl.class)
                .getServiceRegistry().getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_INSERT, this);
        registry.appendListeners(EventType.POST_UPDATE, this);
        registry.appendListeners(EventType.POST_DELETE, this);
    }

    @Override
    public void onPostInsert(PostInsertEvent e) {
        if (skip(e.getEntity())) return;
        write(e.getEntity(), e.getId(), "INSERT", null, snapshot(e.getPersister(), e.getState(), null));
    }

    @Override
    public void onPostUpdate(PostUpdateEvent e) {
        if (skip(e.getEntity())) return;
        int[] dirty = e.getDirtyProperties();
        if (dirty != null && dirty.length == 0) return; // null = unknown, log every property
        Map<String, Object> before = snapshot(e.getPersister(), e.getOldState(), dirty);
        Map<String, Object> after = snapshot(e.getPersister(), e.getState(), dirty);
        if (after.isEmpty()) return; // only sensitive fields changed (e.g. a password) - log nothing
        write(e.getEntity(), e.getId(), "UPDATE", before, after);
    }

    @Override
    public void onPostDelete(PostDeleteEvent e) {
        if (skip(e.getEntity())) return;
        write(e.getEntity(), e.getId(), "DELETE", snapshot(e.getPersister(), e.getDeletedState(), null), null);
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false; // run inside the transaction
    }

    // ------------------------------------------------------------------

    /** Set while the bulk demo dataset is loading so thousands of seed rows do not flood the audit log. */
    public static volatile boolean suspended = false;

    private boolean skip(Object entity) {
        return suspended || entity == null || SKIPPED_ENTITIES.contains(entity.getClass().getSimpleName());
    }

    private static boolean sensitive(String property) {
        String p = property.toLowerCase(Locale.ROOT);
        return p.contains("password") || p.contains("secret") || p.contains("token");
    }

    /** Property name -> simple value. Relations become their id; collections are skipped. */
    private Map<String, Object> snapshot(EntityPersister persister, Object[] state, int[] only) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (state == null) return out;
        String[] names = persister.getPropertyNames();
        PersistenceUnitUtil util = emf.getPersistenceUnitUtil();
        int[] idx = only;
        if (idx == null) {
            idx = new int[names.length];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
        }
        for (int i : idx) {
            if (i >= state.length || sensitive(names[i])) continue;
            Object v = state[i];
            if (v instanceof Collection || v instanceof Map) continue;
            if (v != null && v.getClass().isAnnotationPresent(Entity.class)) {
                v = util.getIdentifier(v);
            } else if (v != null && !(v instanceof Number) && !(v instanceof Boolean) && !(v instanceof String)) {
                v = v.toString();
            }
            out.put(names[i], v);
        }
        return out;
    }

    private void write(Object entity, Object id, String op, Map<String, Object> oldV, Map<String, Object> newV) {
        try {
            if (!(id instanceof Number)) return;
            Table t = entity.getClass().getAnnotation(Table.class);
            String table = t != null ? t.name() : entity.getClass().getSimpleName().toLowerCase(Locale.ROOT);

            Long userId = null;
            String username = "system";
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) {
                userId = p.getUser().getId();
                username = p.getUser().getUsername();
            }
            jdbc.update("INSERT INTO audit_log (table_name, record_id, operation, user_id, username, old_values, new_values, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    table, ((Number) id).longValue(), op, userId, username,
                    oldV == null ? null : mapper.writeValueAsString(oldV),
                    newV == null ? null : mapper.writeValueAsString(newV),
                    Timestamp.valueOf(LocalDateTime.now()));
        } catch (Exception ex) {
            // Auditing must never take the business operation down with it.
            log.warn("Could not write audit log entry for {}: {}", entity.getClass().getSimpleName(), ex.getMessage());
        }
    }
}
