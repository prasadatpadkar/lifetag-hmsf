package com.lifetag.hms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "billings")
@Data
@NoArgsConstructor
public class Billing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Size(max = 255, message = "Description must be at most 255 characters.")
    private String description;

    @DecimalMin(value = "0.0", message = "Amount cannot be negative.")
    @DecimalMax(value = "99999999.99", message = "Amount is too large.")
    private BigDecimal amount;

    @DecimalMin(value = "0.0", message = "Discount cannot be negative.")
    @DecimalMax(value = "99999999.99", message = "Discount is too large.")
    private BigDecimal discount = BigDecimal.ZERO;

    @DecimalMin(value = "0.0", message = "Amount paid cannot be negative.")
    @DecimalMax(value = "99999999.99", message = "Amount paid is too large.")
    private BigDecimal amountPaid = BigDecimal.ZERO;

    /** Paid, Pending, Partial, Cancelled */
    @Size(max = 30, message = "Status is too long.")
    private String status = "Pending";

    @Size(max = 30, message = "Payment method is too long.")
    private String paymentMethod;

    /** JSON-encoded array of line items:
     *  [{"category":"Doctor Fees","desc":"Consultation - Cardiology","qty":1,"amount":800,"taxPct":0}, ...]
     *  where `amount` is the UNIT price and the line total is qty * amount (+ taxPct).
     *  `amount` above is the grand total (subtotal - discount + tax), so other modules
     *  (Reports) that only read `amount` keep working unchanged. */
    @Column(columnDefinition = "TEXT")
    private String itemsJson;

    /** Sum of the line items before discount and tax. */
    @DecimalMin(value = "0.0")
    private BigDecimal subtotal;

    /** Total tax (GST on pharmacy / consumables) across the line items. */
    @DecimalMin(value = "0.0")
    private BigDecimal taxAmount = BigDecimal.ZERO;

    /** Portion of the grand total settled directly by an insurer or government scheme. The
     *  patient's own payable is amount - insuranceCovered. */
    @DecimalMin(value = "0.0")
    private BigDecimal insuranceCovered = BigDecimal.ZERO;

    /** Free text, e.g. the policy / scheme the cover came from. Filled in automatically from the
     *  sanctioned claims - never typed by hand. */
    @Size(max = 500)
    private String insuranceNote;

    /** JSON breakdown of every insurance / scheme claim raised against this invoice:
     *  [{"kind":"INSURANCE","claimId":3,"source":"Care Health (CH-1)","status":"Approved",
     *    "claimed":20000,"sanctioned":18000,"deducted":18000}, ...]
     *  Only claims that are Approved / sanctioned are deducted; the rest are shown as pending. */
    @Column(columnDefinition = "TEXT")
    private String coverageJson;

    /** Insurance / government amount claimed but NOT yet sanctioned. It is shown to the patient as
     *  "awaiting sanction" and is deliberately NOT deducted from what they owe. */
    @DecimalMin(value = "0.0")
    private BigDecimal coveragePending = BigDecimal.ZERO;

    /** Money the patient has paid in excess of what they finally owe (e.g. they paid in full before
     *  the insurer sanctioned a claim). To be refunded or adjusted by the billing desk. */
    @DecimalMin(value = "0.0")
    private BigDecimal refundDue = BigDecimal.ZERO;

    /** JSON array of the clinical records this invoice was built from, so they can be released
     *  again (un-billed) if the invoice is cancelled or deleted: [{"type":"REPORT","id":4}, ...] */
    @Column(columnDefinition = "TEXT")
    private String sourceRefsJson;

    /** Attending / billing doctor shown on the invoice. */
    @ManyToOne
    @JoinColumn(name = "doctor_id")
    private Doctor doctor;

    /** Inpatient stay this invoice covers, if any (drives the admit/discharge dates on the invoice). */
    @ManyToOne
    @JoinColumn(name = "admission_id")
    private Admission admission;

    private LocalDate dueDate;

    @Size(max = 500)
    private String notes;

    /** JSON array of payments received: [{"date":"2026-10-01","amount":500,"method":"UPI","ref":"..."}] */
    @Column(columnDefinition = "TEXT")
    private String paymentsJson;

    private LocalDate billDate = LocalDate.now();
}
