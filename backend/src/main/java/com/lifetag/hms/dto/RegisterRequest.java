package com.lifetag.hms.dto;

import com.lifetag.hms.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank
    @Size(min = 3, max = 50, message = "Username must be 3 to 50 characters")
    private String username;

    @NotBlank
    @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters")
    private String password;

    @Email
    @NotBlank
    private String email;

    @NotBlank
    private String fullName;

    /** Ignored by AuthController#register — public self-registration is always
     *  created as PATIENT regardless of what is sent here. Kept only so older
     *  clients that still send this field don't fail to deserialize. */
    private Role role = Role.PATIENT;
}
