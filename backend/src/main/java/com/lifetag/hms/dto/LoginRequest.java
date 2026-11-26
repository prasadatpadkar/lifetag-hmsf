package com.lifetag.hms.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {
    @NotBlank
    private String username;

    @NotBlank
    private String password;

    /** "Remember this device" ticked on the login form: issues a longer-lived token. */
    private boolean remember;
}
