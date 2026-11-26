
// ============================================
// CLAIM IMPACT — shows, next to a claim form, what the claim does to the patient's bill.
// Cover is only deducted once a claim is Approved (sanctioned); the server enforces every limit,
// this just lets Admin / Reception see the numbers before they save.
//   showClaimImpact(el, { invoiceRef, amount, policyId, approving })
// ============================================
(function () {
  const inr = (n) => '₹' + (Number(n) || 0).toLocaleString('en-IN', { maximumFractionDigits: 2 });

  window.showClaimImpact = async function (el, opts) {
    if (!el) return;
    const m = /^\s*INV-0*(\d+)\s*$/i.exec(opts.invoiceRef || '');
    if (!m) {
      el.innerHTML = opts.invoiceRef
        ? 'Enter the invoice number like <b>INV-0007</b>.'
        : '';
      return;
    }

    el.textContent = 'Checking the invoice…';

    try {
      const inv = await LifetagAPI.getInvoice(Number(m[1]));
      const t = inv.totals;
      const amount = Number(opts.amount) || 0;
      let limitNote = '';
      let cap = Infinity;

      if (opts.policyId) {
        try {
          const policies = await LifetagAPI.getInsurance();
          const p = policies.find(x => x.id === Number(opts.policyId));

          if (p && p.remainingAmount != null) {
            limitNote = ` · policy sum insured left <b>${inr(p.remainingAmount)}</b> of ${inr(p.coverageAmount)}`;
            cap = Number(p.remainingAmount);
          }
        } catch (e) {
          /* the server still enforces the limit */
        }
      }

      const alreadyCovered = Number(t.insuranceCovered) || 0;
      const room = Math.max(0, Number(t.grandTotal) - alreadyCovered);
      const effective = Math.min(amount, cap, room);
      const after = Math.max(
        0,
        Number(t.grandTotal) - alreadyCovered - (opts.approving ? effective : 0)
      );

      el.innerHTML = `Invoice <b>${escapeHtml(inv.invoiceNo || '')}</b> · ${inr(t.grandTotal)} total` +
        (alreadyCovered ? ` · already covered ${inr(alreadyCovered)}` : '') + limitNote +
        (amount > room
          ? `<br><span style="color:var(--coral-500);">The amount is more than what is left on the invoice (${inr(room)}).</span>`
          : '') +
        (opts.approving
          ? `<br>If sanctioned: <b>${inr(effective)}</b> is deducted → the patient pays <b>${inr(after)}</b> (paid so far ${inr(t.paid)}).`
          : `<br>Nothing is deducted until the claim is <b>Approved</b>; the patient still owes ${inr(Number(t.patientPayable))} for now.`);
    } catch (err) {
      el.textContent = `Couldn't read that invoice: ${err.message}`;
    }
  };
})();
