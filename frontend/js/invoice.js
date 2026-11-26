// ============================================
// ITEMISED INVOICE — one printable page for every role that may see a bill.
// Data comes from GET /api/billing/{id}/invoice (see BillingController#invoice):
// the server already computed line totals, tax, the per-category "where the money
// went" breakdown and the balance, so this page only renders it.
// Staff (Admin / Reception) can also record a payment here.
// ============================================

const params = new URLSearchParams(window.location.search);
const invoiceId = Number(params.get('id'));
const role = (localStorage.getItem('lifetag_role') || '').toUpperCase();
const root = document.getElementById('invoice');

const BACK = { ADMIN: 'billing-payment.html', RECEPTION: 'reception-billing.html', PATIENT: 'patient-billing.html' };
document.getElementById('backLink').href = BACK[role] || 'index.html';
document.getElementById('printBtn').addEventListener('click', () => window.print());

const inr = (n) => '₹' + (Number(n) || 0).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const fdate = (d) => d ? new Date(d + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }) : '—';

function render(inv) {
  const b = inv.bill, t = inv.totals, h = inv.hospital;
  const patient = b.patient || {};
  const doctor = b.doctor;
  const adm = b.admission;
  const status = (b.status || '').toLowerCase();

  // group the line items by category, in the order they first appear
  const groups = new Map();
  inv.items.forEach(it => {
    if (!groups.has(it.category)) groups.set(it.category, []);
    groups.get(it.category).push(it);
  });
  let n = 0;
  const rows = [];
  groups.forEach((list, cat) => {
    const catTotal = list.reduce((s, x) => s + Number(x.lineTotal), 0);
    rows.push(`<tr class="cat-row"><td colspan="5">${escapeHtml(cat)}</td><td class="num">${inr(catTotal)}</td></tr>`);
    list.forEach(it => {
      n += 1;
      rows.push(`<tr>
        <td>${n}</td>
        <td>${escapeHtml(it.desc)}</td>
        <td class="num">${escapeHtml(it.qty)}</td>
        <td class="num">${inr(it.rate)}</td>
        <td class="num">${Number(it.taxPct) ? escapeHtml(Number(it.taxPct)) + '%' : '—'}</td>
        <td class="num">${inr(it.lineTotal)}</td>
      </tr>`);
    });
  });
  if (!inv.items.length) rows.push(`<tr><td colspan="6">${escapeHtml(b.description || 'Charges')}</td></tr>`);

  const spend = inv.categorySummary.map(c => `
    <div class="spend-row">
      <div class="spend-row__top"><span>${escapeHtml(c.category)}</span><span><b>${inr(c.amount)}</b> &middot; ${escapeHtml(Number(c.percent))}%</span></div>
      <div class="spend-bar"><i style="width:${Math.max(2, Math.min(100, Number(c.percent)))}%"></i></div>
    </div>`).join('') || '<div style="color:#5C7079;">No itemised charges.</div>';

  const pays = inv.payments.length ? `
    <div class="inv-section">Payments received</div>
    <table class="inv-table"><thead><tr><th>Date</th><th>Method</th><th>Reference</th><th class="num">Amount</th></tr></thead><tbody>
      ${inv.payments.map(p => `<tr><td>${escapeHtml(fdate(p.date))}</td><td>${escapeHtml(p.method)}</td><td>${escapeHtml(p.ref || '—')}</td><td class="num">${inr(p.amount)}</td></tr>`).join('')}
    </tbody></table>` : '';

  // Insurance / government cover: each claim, what was sanctioned, and what is still waiting
  const cov = inv.coverage || [];
  const coverTable = cov.length ? `
    <div class="inv-section">Insurance &amp; government scheme cover</div>
    <table class="inv-table"><thead><tr><th>Source</th><th>Claim status</th><th class="num">Claimed</th><th class="num">Sanctioned</th><th class="num">Deducted from bill</th></tr></thead><tbody>
      ${cov.map(c => `<tr>
        <td>${escapeHtml(c.kind === 'SCHEME' ? 'Govt scheme' : 'Insurance')} &middot; ${escapeHtml(c.source)}</td>
        <td>${escapeHtml(c.status)}</td>
        <td class="num">${inr(c.claimed)}</td>
        <td class="num">${Number(c.sanctioned) > 0 ? inr(c.sanctioned) : '—'}</td>
        <td class="num">${Number(c.deducted) > 0 ? '− ' + inr(c.deducted) : 'Not deducted'}</td>
      </tr>`).join('')}
    </tbody></table>
    <div style="font-size:12px; color:#5C7079; margin-top:6px;">Cover is deducted only once the insurer / scheme has sanctioned the amount. Until then the full amount stays payable by the patient.</div>` : '';

  const stay = adm ? `
    <div class="inv-box"><h4>Admission</h4>
      <div class="big">${escapeHtml(adm.wardType || 'Inpatient stay')}${adm.bedNumber ? ' &middot; bed ' + escapeHtml(adm.bedNumber) : ''}</div>
      <div>Admitted: ${escapeHtml(fdate(adm.admittedOn))}</div>
      <div>Discharged: ${escapeHtml(adm.dischargedOn ? fdate(adm.dischargedOn) : 'Still admitted')}</div>
      <div>${escapeHtml(adm.diagnosis || adm.reason || '')}</div>
    </div>` : `
    <div class="inv-box"><h4>Visit</h4>
      <div class="big">Outpatient (OPD)</div>
      <div>Invoice date: ${escapeHtml(fdate(b.billDate))}</div>
      <div>Due date: ${escapeHtml(fdate(b.dueDate))}</div>
    </div>`;

  root.innerHTML = `
    <div class="inv-top">
      <div class="inv-brand">${escapeHtml(h.name)}
        <small>${escapeHtml(h.address)}<br>Tel ${escapeHtml(h.phone)} &middot; ${escapeHtml(h.email)}<br>GSTIN ${escapeHtml(h.gstin)}</small>
      </div>
      <div class="inv-title">
        <h1>INVOICE</h1>
        <div class="inv-no">${escapeHtml(inv.invoiceNo)}</div>
        <div>Date: ${escapeHtml(fdate(b.billDate))}</div>
        <span class="inv-status ${escapeHtml(status)}">${escapeHtml(b.status)}</span>
      </div>
    </div>

    <div class="inv-parties">
      <div class="inv-box"><h4>Billed to</h4>
        <div class="big">${escapeHtml(patient.name || '—')}</div>
        <div>Patient ID: LT-${escapeHtml(String(patient.id || 0).padStart(4, '0'))}</div>
        <div>${escapeHtml(patient.age != null ? patient.age + ' yrs' : '')}${patient.gender ? ' &middot; ' + escapeHtml(patient.gender) : ''}${patient.bloodGroup ? ' &middot; ' + escapeHtml(patient.bloodGroup) : ''}</div>
        <div>${escapeHtml(patient.contact || '')}</div>
        <div>${escapeHtml(patient.address || '')}</div>
      </div>
      ${stay}
    </div>
    <div class="inv-parties" style="margin-top:-6px;">
      <div class="inv-box"><h4>${adm ? 'Attending doctor' : 'Consulting doctor'}</h4>
        <div class="big">${escapeHtml(doctor ? doctor.name : '—')}</div>
        <div>${escapeHtml(doctor ? [doctor.specialization, doctor.qualification].filter(Boolean).join(' · ') : '')}</div>
      </div>
      <div class="inv-box"><h4>Payment terms</h4>
        <div>Due by: <b>${escapeHtml(fdate(b.dueDate))}</b></div>
        <div>Last payment mode: ${escapeHtml(b.paymentMethod || '—')}</div>
        ${b.insuranceNote ? `<div>Sanctioned cover: ${escapeHtml(b.insuranceNote)}</div>` : ''}
      </div>
    </div>

    <div class="inv-section">Itemised charges</div>
    <table class="inv-table">
      <thead><tr><th>#</th><th>Description</th><th class="num">Qty</th><th class="num">Rate</th><th class="num">GST</th><th class="num">Amount</th></tr></thead>
      <tbody>${rows.join('')}</tbody>
    </table>

    <div class="inv-split" style="margin-top:26px;">
      <div>
        <div class="inv-section" style="margin-top:0;">Where the money went</div>
        ${spend}
      </div>
      <div>
        <div class="inv-section" style="margin-top:0;">Summary</div>
        <div class="inv-totals">
          <div class="row"><span>Subtotal</span><span>${inr(t.subtotal)}</span></div>
          ${Number(t.discount) ? `<div class="row"><span>Discount</span><span>− ${inr(t.discount)}</span></div>` : ''}
          <div class="row"><span>GST / tax</span><span>${inr(t.tax)}</span></div>
          <div class="row grand"><span>Grand total</span><span>${inr(t.grandTotal)}</span></div>
          ${Number(t.insuranceCovered) ? `<div class="row"><span>Insurance / scheme cover (sanctioned)</span><span>− ${inr(t.insuranceCovered)}</span></div>` : ''}
          <div class="row${Number(t.insuranceCovered) ? ' grand' : ''}"><span>Patient payable</span><span>${inr(t.patientPayable)}</span></div>
          <div class="row"><span>Paid</span><span>− ${inr(t.paid)}</span></div>
          <div class="row balance ${Number(t.balance) === 0 ? 'zero' : ''}"><span>Balance due</span><span>${inr(t.balance)}</span></div>
          ${Number(t.coveragePending) > 0 ? `<div class="row" style="font-size:12.5px; color:#8A6D1B;"><span>Awaiting insurer / scheme sanction</span><span>${inr(t.coveragePending)}</span></div>
          <div class="row" style="font-size:12.5px; color:#8A6D1B;"><span>Balance if fully sanctioned</span><span>${inr(t.expectedBalanceIfSanctioned)}</span></div>` : ''}
          ${Number(t.refundDue) > 0 ? `<div class="row" style="color:#C9402C;"><span>Refund due to patient</span><span>${inr(t.refundDue)}</span></div>` : ''}
        </div>
      </div>
    </div>

    ${coverTable}
    ${pays}
    ${b.notes ? `<div class="inv-section">Notes</div><div>${escapeHtml(b.notes)}</div>` : ''}

    <div class="inv-foot">
      <div>This is a computer-generated invoice. Medical services are GST-exempt; GST applies to pharmacy and consumables only.<br>Please quote ${escapeHtml(inv.invoiceNo)} for any billing query.</div>
      <div class="inv-sign"><div>Authorised signatory</div></div>
    </div>`;
  document.title = `${inv.invoiceNo} — ${patient.name || 'Invoice'}`;

  const isStaff = role === 'ADMIN' || role === 'RECEPTION';
  const payBtn = document.getElementById('payBtn');
  payBtn.style.display = isStaff && Number(t.balance) > 0 && b.status !== 'Cancelled' ? '' : 'none';
  document.getElementById('payAmount').value = Number(t.balance) || '';
}

async function load() {
  try {
    if (!invoiceId) throw new Error('No invoice selected.');
    render(await LifetagAPI.getInvoice(invoiceId));
  } catch (err) {
    root.innerHTML = `<p style="text-align:center; color:#C9402C;">Couldn't load this invoice: ${escapeHtml(err.message)}</p>`;
  }
}

document.getElementById('payBtn').addEventListener('click', () => {
  const box = document.getElementById('payBox');
  box.style.display = box.style.display === 'none' ? 'flex' : 'none';
});
document.getElementById('paySave').addEventListener('click', async () => {
  const msg = document.getElementById('payMsg');
  msg.textContent = '';
  try {
    await LifetagAPI.addPayment(invoiceId, {
      amount: Number(document.getElementById('payAmount').value),
      method: document.getElementById('payMethod').value,
      reference: document.getElementById('payRef').value.trim()
    });
    document.getElementById('payBox').style.display = 'none';
    await load();
  } catch (err) {
    msg.textContent = err.message;
  }
});

load();
