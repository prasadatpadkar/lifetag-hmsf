# Billing, auto-charges and insurance / scheme cover — what changed

## 1. Admission, room/bed and blood charges now reach the bill automatically
* **One running inpatient invoice per stay.** Admitting a patient (Admissions screen *or* allotting a bed on
  Room & Bed) opens an invoice that keeps itself up to date: room/bed days (at the tariff fixed when the bed
  was allotted), nursing, daily doctor visits, **blood issued**, diagnostic reports done during the stay,
  procedures / OT / medicines of the stay's treatments. Lines are tagged `auto`, are read-only in the billing
  form, and are rebuilt by the server; hand-typed lines are never touched. Bed days accrue daily until discharge.
* **Room & Bed screen is wired to admissions.** Allotting a bed admits the patient (or transfers them if they
  already hold another bed); vacating / taking an occupied bed out of service discharges them and finalises the
  bill. A bed can no longer be "Occupied" without an admission behind it; an occupied bed can't be deleted.
* **Blood bank.** Each stock row has a price per unit and an expiry date; status is derived (Available / Low /
  Out of Stock / Expired). Marking a request **Fulfilled** checks stock (non-expired, oldest expiry first),
  deducts it, prices it, links it to the patient's current stay and puts it on the invoice (outpatient blood is
  offered as a ready-made charge when the next invoice is made). Fulfilled/cancelled requests are locked.
  Doctors can now raise blood requests for their own patients (patient record → Admissions → *Request blood*).
* Cancelling or deleting an invoice releases the tests / blood / bed days so they can be billed again;
  an invoice with payments or claims can't be deleted/cancelled (refund / withdraw first).

## 2. Insurance and government scheme cover
* Cover is **no longer typed into an invoice**. It is derived from claims filed against the invoice
  (`invoiceRef` = `INV-0005`) and is **deducted only when a claim is Approved / sanctioned**.
  Submitted / Under Review claims show as *awaiting sanction* and are **not** deducted.
* Limits enforced when a claim is sanctioned: not more than claimed; not more than the policy's sum insured
  still left (policy balance shown on the policy); not more than the scheme's fixed ceiling per year
  (`Scheme.maxCoverAmount`, e.g. 5,00,000 for Ayushman / MJPJAY); not more than what is left on the invoice.
* Filing a claim validates the invoice (exists, same patient, not cancelled), the amount, the policy period on
  the treatment date, and blocks duplicate open claims for the same invoice + policy/scheme.
* Sanctioning / rejecting / withdrawing a claim re-prices the invoice immediately and notifies the patient:
  *Bill − sanctioned cover = patient pays*. If the patient had already paid more, the excess is shown as
  **Refund due**.
* Admin, Reception and the Patient all see the same figures: invoice page (cover table, patient payable,
  balance, awaiting-sanction, balance-if-sanctioned, refund due), billing lists, patient "My Bills", and the
  claim review / filing screens show the effect on the bill before saving.

## 3. Other fixes
* Dashboards: reception and patient "outstanding" now = bill − sanctioned cover − paid (was wrong).
* Editing a donor no longer wipes expiry / price; blood groups, units and statuses are validated.
* Patient records can't be deleted while admitted or with unpaid bills; insurance policies with claims can't be
  deleted; policy dates / sum insured validated; admission dates can't be in the future; a patient can't be
  admitted twice.
* Existing data is repaired on first start (`BillingDataMigrator`): scheme ceilings, blood prices, admissions for
  beds already marked occupied, blood already issued gets priced/linked, running invoices opened for open stays,
  and cover recomputed from sanctioned claims only. The seed dataset (`large-dataset.json`) no longer carries
  typed-in cover (`tools/align_seed_with_billing_rules.py`).

## Upgrading an existing database
With `ddl-auto=update` (default) nothing to do. Otherwise run `backend/sql/migration_003_auto_billing_and_cover.sql`.
