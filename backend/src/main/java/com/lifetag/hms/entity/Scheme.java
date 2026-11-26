package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "schemes")
@Data
@NoArgsConstructor
public class Scheme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name is required.")
    @Size(max = 150, message = "Name must be at most 150 characters.")
    @Column(nullable = false)
    private String name;

    @Size(max = 2000, message = "Description must be at most 2000 characters.")
    @Column(length = 2000)
    private String description;

    @Size(max = 255, message = "Eligibility must be at most 255 characters.")
    private String eligibility;

    @Size(max = 255, message = "Benefits must be at most 255 characters.")
    private String benefits;

    @Size(max = 50, message = "Category is too long.")
    private String category;

    @Size(max = 30, message = "Status is too long.")
    private String status = "Active";

    /** Fixed ceiling the scheme pays per claim / per family per year (e.g. 500000 for Ayushman
     *  Bharat). A sanctioned amount can never exceed this. Empty = no fixed ceiling. */
    @DecimalMin(value = "0.0", message = "Maximum cover cannot be negative.")
    @DecimalMax(value = "99999999.99", message = "Maximum cover is too large.")
    private java.math.BigDecimal maxCoverAmount;
}
