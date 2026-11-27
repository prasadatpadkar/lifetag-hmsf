package com.lifetag.hms.config;

import com.lifetag.hms.security.JwtAuthFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private JwtAuthFilter jwtAuthFilter;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/public/**").permitAll()
                // Back-office modules: only Admin and Reception ever use these screens.
                // Patients and Doctors get a 403 even if they call the API directly.
                // Doctors may raise and see blood requests for their own patients (the controller
                // checks the appointment link); everything else on blood requests is staff-only.
                .requestMatchers(HttpMethod.GET, "/api/blood-requests/**").hasAnyRole("ADMIN", "RECEPTION", "DOCTOR")
                .requestMatchers(HttpMethod.POST, "/api/blood-requests/**").hasAnyRole("ADMIN", "RECEPTION", "DOCTOR")
                .requestMatchers("/api/rooms-beds/**", "/api/blood-bank/**", "/api/blood-requests/**")
                    .hasAnyRole("ADMIN", "RECEPTION")
                // Money / cover data is never a doctor's business. Without this, a doctor could call
                // e.g. /api/billing/patient/{id} for ANY patient, not just the ones they treat.
                .requestMatchers("/api/billing/**", "/api/insurance/**", "/api/insurance-claims/**",
                        "/api/patient-schemes/**", "/api/scheme-claims/**")
                    .hasAnyRole("ADMIN", "RECEPTION", "PATIENT")
                // Hospital-wide analytics (revenue etc.) are Admin-only.
                .requestMatchers("/api/reports/**").hasRole("ADMIN")
                // Audit trail and menu management are administrator-only.
                .requestMatchers("/api/audit-logs/**", "/api/admin/**").hasRole("ADMIN")
                // Every signed-in role WRITES to the activity log (logActivity in
                // dashboard.js), but only Admin can READ it (Staff Activity page).
                .requestMatchers(HttpMethod.GET, "/api/staff-activity/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of(allowedOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
