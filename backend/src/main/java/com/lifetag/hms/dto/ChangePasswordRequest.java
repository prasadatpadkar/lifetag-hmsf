package com.lifetag.hms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Replaces the old email-token "forgot password" flow. Proving you already know
 * the account's current (or temporary) password is what makes this endpoint safe
 * to expose without a login — a username/email alone is never enough to change
 * someone else's password. See AuthController#changePassword.
 */
@Data
public class ChangePasswordRequest {
    @NotBlank
    private String username;

    @NotBlank
    private String oldPassword;

    @NotBlank
    @Size(min = 8, max = 72, message = "New password must be 8 to 72 characters")
    private String newPassword;
}
