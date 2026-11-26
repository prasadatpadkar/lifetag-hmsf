package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "rooms_beds")
@Data
@NoArgsConstructor
public class RoomBed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Room number is required.")
    @Size(max = 30, message = "Room number is too long.")
    @Column(nullable = false)
    private String roomNumber;

    @Size(max = 30, message = "Bed number is too long.")
    private String bedNumber;

    /** General, ICU, Private, Semi-Private */
    @Size(max = 50, message = "Room type is too long.")
    private String roomType;

    /** Available, Occupied, Maintenance */
    @Size(max = 30, message = "Status is too long.")
    private String status = "Available";

    @ManyToOne
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @DecimalMin(value = "0.0", message = "Charge per day cannot be negative.")
    private Double chargePerDay;
}
