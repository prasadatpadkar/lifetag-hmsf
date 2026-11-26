package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One sidebar link, stored in the database and managed by administrators. */
@Entity
@Table(name = "menu_items",
        indexes = @Index(name = "idx_menu_role", columnList = "role, sort_order"),
        uniqueConstraints = @UniqueConstraint(name = "uq_menu_role_url", columnNames = {"role", "url"}))
@Data
@NoArgsConstructor
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Which portal's sidebar shows this link. */
    @NotNull(message = "Role is required.")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Size(max = 40, message = "Group must be at most 40 characters.")
    @Pattern(regexp = "^[A-Za-z0-9 &/-]*$", message = "Group may contain only letters, digits, spaces, & / -.")
    @Column(name = "group_label", length = 40)
    private String groupLabel;

    @NotBlank(message = "Label is required.")
    @Size(max = 40, message = "Label must be at most 40 characters.")
    @Pattern(regexp = "^[A-Za-z0-9 &/().,'-]*$", message = "Label contains characters that are not allowed.")
    @Column(nullable = false, length = 40)
    private String label;

    /** Page file name inside /pages/, e.g. "billing-payment.html". No paths, no schemes. */
    @NotBlank(message = "Page is required.")
    @Pattern(regexp = "^[A-Za-z0-9_-]{1,60}\\.html$", message = "Page must be a file name like billing-payment.html.")
    @Column(nullable = false, length = 80)
    private String url;

    /** Key into the icon set in js/menu.js. */
    @Size(max = 30)
    @Pattern(regexp = "^[a-z0-9-]*$", message = "Icon must be a lowercase key.")
    @Column(length = 30)
    private String icon = "dot";

    @Min(value = 0, message = "Order must be 0 or more.")
    @Max(value = 9999, message = "Order must be at most 9999.")
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(nullable = false)
    private boolean active = true;
}
