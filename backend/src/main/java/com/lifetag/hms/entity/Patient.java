package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "patients")
@Data
@NoArgsConstructor
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name is required.")
    @Size(max = 100, message = "Name must be at most 100 characters.")
    @Column(nullable = false)
    private String name;

    @Min(value = 0, message = "Age must be 0 or more.")
    @Max(value = 150, message = "Age must be 150 or less.")
    private Integer age;

    @Size(max = 20, message = "Gender is too long.")
    private String gender;

    @Pattern(regexp = "^[0-9+()\\- ]{0,20}$", message = "Contact may contain only digits, spaces, + ( ) - and be at most 20 characters.")
    private String contact;

    @Email(message = "Enter a valid email address.")
    @Size(max = 120, message = "Email must be at most 120 characters.")
    private String email;

    @Size(max = 255, message = "Address must be at most 255 characters.")
    private String address;

    @Size(max = 10, message = "Blood group is too long.")
    private String bloodGroup;

    @Pattern(regexp = "^[0-9+()\\- ]{0,20}$", message = "Emergency contact may contain only digits, spaces, + ( ) - and be at most 20 characters.")
    private String emergencyContact;

    /** Known allergies (drugs, food, latex...), shown prominently on the clinical record. */
    @Size(max = 500, message = "Allergies must be at most 500 characters.")
    private String allergies;

    /** Long-term conditions, e.g. "Hypertension, Type 2 Diabetes". */
    @Size(max = 500, message = "Chronic conditions must be at most 500 characters.")
    private String chronicConditions;

    private LocalDate registrationDate = LocalDate.now();

    @Size(max = 30, message = "Status is too long.")
    private String status = "Active";

    /** Optional link to the login account, if the patient has portal access */
    @OneToOne
    @JoinColumn(name = "user_id")
    private User user;

    /** Populated only in the response right after Admin/Reception creates this
     *  patient with an email, so the UI can show the one-time login to hand over.
     *  Never persisted, never re-served by a GET afterwards. */
    @Transient
    private String tempUsername;

    @Transient
    private String tempPassword;
}
