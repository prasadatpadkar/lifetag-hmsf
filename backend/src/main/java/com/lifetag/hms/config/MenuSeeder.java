package com.lifetag.hms.config;

import com.lifetag.hms.entity.MenuItem;
import com.lifetag.hms.entity.Role;
import com.lifetag.hms.repository.MenuItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Fills the menu_items table with the standard sidebar of each portal, but only when
 * the table is empty - so an upgraded database gets the default menu once, and later
 * changes made by administrators are never overwritten.
 */
@Component
@Order(1)
public class MenuSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MenuSeeder.class);

    // role, group, label, page, icon, order
    private static final String[][] DEFAULTS = {
            {"ADMIN", "OVERVIEW", "Dashboard", "admin-dashboard.html", "dashboard", "10"},
            {"ADMIN", "CARE MODULES", "Patients", "patient-management.html", "patients", "20"},
            {"ADMIN", "CARE MODULES", "Doctors", "doctor-management.html", "doctors", "30"},
            {"ADMIN", "CARE MODULES", "Appointments", "appointment-management.html", "calendar", "40"},
            {"ADMIN", "CARE MODULES", "Room & Bed", "room-bed-management.html", "bed", "50"},
            {"ADMIN", "CARE MODULES", "Blood Bank", "blood-bank-management.html", "blood", "60"},
            {"ADMIN", "OPERATIONS", "Billing", "billing-payment.html", "billing", "70"},
            {"ADMIN", "OPERATIONS", "Insurance", "insurance-management.html", "shield", "80"},
            {"ADMIN", "OPERATIONS", "Government Schemes", "schemes-management.html", "bank", "90"},
            {"ADMIN", "OPERATIONS", "Reports", "reports-analytics.html", "chart", "100"},
            {"ADMIN", "OPERATIONS", "Feedback", "feedback.html", "feedback", "110"},
            {"ADMIN", "OPERATIONS", "Staff Activity", "staff-activity.html", "clock", "120"},
            {"ADMIN", "ADMINISTRATION", "Staff Accounts", "staff-management.html", "users", "130"},
            {"RECEPTION", "OVERVIEW", "Dashboard", "reception-dashboard.html", "dashboard", "10"},
            {"RECEPTION", "FRONT DESK", "Patients", "reception-patients.html", "patients", "20"},
            {"RECEPTION", "FRONT DESK", "Appointments", "reception-appointments.html", "calendar", "30"},
            {"RECEPTION", "FRONT DESK", "Room & Bed", "reception-room-bed.html", "bed", "40"},
            {"RECEPTION", "FRONT DESK", "Blood Bank", "reception-blood-bank.html", "blood", "48"},
            {"RECEPTION", "FRONT DESK", "Billing", "reception-billing.html", "billing", "50"},
            {"RECEPTION", "FRONT DESK", "Insurance", "reception-insurance.html", "shield", "60"},
            {"RECEPTION", "FRONT DESK", "Government Schemes", "reception-schemes.html", "bank", "70"},
            {"DOCTOR", "OVERVIEW", "Dashboard", "doctor-dashboard.html", "dashboard", "10"},
            {"DOCTOR", "MY CARE", "My Patients", "doctor-patients.html", "patients", "20"},
            {"DOCTOR", "MY CARE", "My Appointments", "doctor-appointments.html", "calendar", "30"},
            {"DOCTOR", "MY CARE", "Feedback", "doctor-feedback.html", "feedback", "40"},
            {"PATIENT", "OVERVIEW", "Dashboard", "patient-dashboard.html", "dashboard", "10"},
            {"PATIENT", "MY CARE", "My Appointments", "patient-appointments.html", "calendar", "20"},
            {"PATIENT", "MY CARE", "My Bills", "patient-billing.html", "billing", "30"},
            {"PATIENT", "MY CARE", "Insurance", "patient-insurance.html", "shield", "40"},
            {"PATIENT", "MY CARE", "Government Schemes", "patient-schemes.html", "bank", "50"},
            {"PATIENT", "MY CARE", "Feedback", "patient-feedback.html", "feedback", "60"},
            {"PATIENT", "MY CARE", "Medical Records", "patient-records.html", "records", "25"},
            {"RECEPTION", "FRONT DESK", "Admissions", "reception-admissions.html", "bed", "45"},
            {"ADMIN", "CARE MODULES", "Admissions", "admission-management.html", "bed", "55"},
            {"ADMIN", "ADMINISTRATION", "Audit Log", "audit-log.html", "audit", "140"},
            {"ADMIN", "ADMINISTRATION", "Menu Management", "menu-management.html", "menu", "150"}
    };

    private final MenuItemRepository repository;

    public MenuSeeder(MenuItemRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        // Fresh database: seed everything. Existing database: only add the pages shipped in later
        // versions (so they show up in the sidebar) and never resurrect items an admin removed.
        boolean fresh = repository.count() == 0;
        java.util.Set<String> newPages = java.util.Set.of(
                "patient-records.html", "reception-admissions.html", "admission-management.html",
                "reception-blood-bank.html");
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (MenuItem m : repository.findAll()) existing.add(m.getRole() + "|" + m.getUrl());
        int added = 0;
        for (String[] r : DEFAULTS) {
            if (existing.contains(r[0] + "|" + r[3])) continue;
            if (!fresh && !newPages.contains(r[3])) continue;
            MenuItem m = new MenuItem();
            m.setRole(Role.valueOf(r[0]));
            m.setGroupLabel(r[1]);
            m.setLabel(r[2]);
            m.setUrl(r[3]);
            m.setIcon(r[4]);
            m.setSortOrder(Integer.parseInt(r[5]));
            m.setActive(true);
            repository.save(m);
            added++;
        }
        if (added > 0) log.info("Seeded {} default menu items.", added);
    }
}
