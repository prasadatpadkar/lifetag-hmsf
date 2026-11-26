package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "blood_bank")
@Data
@NoArgsConstructor
public class BloodBank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Blood group is required.")
    @Size(max = 10, message = "Blood group is too long.")
    @Column(nullable = false)
    private String bloodGroup;

    @Min(value = 0, message = "Units cannot be negative.")
    @Max(value = 100000, message = "Units value is too large.")
    private Integer unitsAvailable = 0;

    @Size(max = 100, message = "Donor name is too long.")
    private String donorName;

    @Pattern(regexp = "^[0-9+()\\- ]{0,20}$", message = "Donor contact may contain only digits, spaces, + ( ) - and be at most 20 characters.")
    private String donorContact;

    private LocalDate lastDonationDate;

    private LocalDate expiryDate;

    @Size(max = 30, message = "Status is too long.")
    private String status = "Available";

    /** Hospital charge per unit issued (screening + processing + storage). Used to bill blood
     *  that is issued to a patient. */
    @DecimalMin(value = "0.0", message = "Price per unit cannot be negative.")
    @DecimalMax(value = "999999.99", message = "Price per unit is too large.")
    private java.math.BigDecimal pricePerUnit = new java.math.BigDecimal("1500.00");
}
