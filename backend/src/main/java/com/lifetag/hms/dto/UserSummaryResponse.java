package com.lifetag.hms.dto;

import com.lifetag.hms.entity.User;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Safe, outward-facing view of a {@link User} account — deliberately leaves out the
 * password hash. Used by StaffController when listing/creating Reception and Admin
 * logins so those endpoints never serialize the raw User entity.
 */
@Data
public class UserSummaryResponse {
    private Long id;
    private String username;
    private String email;
    private String fullName;
    private String role;
    private boolean enabled;
    private LocalDateTime createdAt;

    /** Only populated on the response returned immediately after account creation. */
    private String tempPassword;

    public static UserSummaryResponse from(User user) {
        UserSummaryResponse dto = new UserSummaryResponse();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setFullName(user.getFullName());
        dto.setRole(user.getRole().name());
        dto.setEnabled(user.isEnabled());
        dto.setCreatedAt(user.getCreatedAt());
        return dto;
    }
}
