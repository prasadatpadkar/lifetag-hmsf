package com.lifetag.hms.service;

import com.lifetag.hms.entity.Billing;
import com.lifetag.hms.repository.BillingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Re-prices an invoice when something that feeds it changes (a claim is sanctioned, rejected or
 *  withdrawn) and tells the patient what they now owe. */
@Service
public class BillingSyncService {

    private final BillingRepository billings;
    private final InvoiceService invoiceService;
    private final NotificationService notifications;

    public BillingSyncService(BillingRepository billings, InvoiceService invoiceService, NotificationService notifications) {
        this.billings = billings;
        this.invoiceService = invoiceService;
        this.notifications = notifications;
    }

    /** Recalculate the invoice named by a claim's invoiceRef ("INV-0005"); no-op if there is none. */
    @Transactional
    public Billing syncInvoiceRef(String invoiceRef, boolean notifyPatient) {
        Billing bill = invoiceRef == null ? null : billings.findById(
                CoverageService.parseInvoiceId(invoiceRef) == null ? -1L : CoverageService.parseInvoiceId(invoiceRef)).orElse(null);
        if (bill == null) return null;
        BigDecimal before = bill.getInsuranceCovered() == null ? BigDecimal.ZERO : bill.getInsuranceCovered();
        invoiceService.recalculate(bill, false);
        Billing saved = billings.save(bill);
        BigDecimal after = saved.getInsuranceCovered() == null ? BigDecimal.ZERO : saved.getInsuranceCovered();
        if (notifyPatient && before.compareTo(after) != 0 && saved.getPatient() != null) {
            BigDecimal payable = saved.getAmount().subtract(after);
            BigDecimal paid = saved.getAmountPaid() == null ? BigDecimal.ZERO : saved.getAmountPaid();
            BigDecimal due = payable.subtract(paid).max(BigDecimal.ZERO);
            String msg = "Insurance / scheme cover of ₹" + after + " has been applied to " + InvoiceService.invoiceNo(saved)
                    + ". Bill ₹" + saved.getAmount() + " − cover ₹" + after + " = you pay ₹" + payable
                    + (due.signum() == 0 ? " (nothing more due)." : " (₹" + due + " still due).")
                    + (saved.getRefundDue() != null && saved.getRefundDue().signum() > 0
                        ? " You paid ₹" + saved.getRefundDue() + " extra - it will be refunded or adjusted." : "");
            notifications.notifyUser(saved.getPatient().getUser(), "Your bill was updated", msg, "BILLING", "invoice.html?id=" + saved.getId());
        }
        return saved;
    }
}
