// ============================================
// BILLING & PAYMENT — live data via LifetagAPI (see js/api-config.js)
//
// One invoice = categorised line items (doctor fees, room & bed, nursing, radiology,
// laboratory, pharmacy, procedures ...) + discount + GST + insurance/scheme cover. The server
// recomputes every total and the Paid / Partial / Unpaid status (BillingController +
// InvoiceService), so the figures shown here are a live preview of what will be saved.
// "Import charges" pulls un-invoiced consultations, bed days, scans, procedures and
// medicines straight from the patient's clinical record.
// ============================================

let invoices = [];
let allPatients = [];
let allDoctors = [];
let categories = [];
let editingId = null;
let importedRefs = [];      // clinical records already turned into line items in this form
let importedAdmissionId = null;
let editingInvoice = null;   // the invoice open in the modal (cover figures come from it - never typed in)

const tableBody = document.getElementById('billTableBody');
const emptyState = document.getElementById('emptyState');
const searchInput = document.getElementById('billSearch');
const statusFilter = document.getElementById('statusFilter');

const overlay = document.getElementById('billModalOverlay');
const modalTitle = document.getElementById('modalTitle');
const form = document.getElementById('billForm');
const toast = document.getElementById('toast');
const toastMessage = document.getElementById('toastMessage');
const patientSelect = document.getElementById('patientSelect');
const doctorSelect = document.getElementById('doctorSelect');
const itemsContainer = document.getElementById('itemsContainer');
const importBox = document.getElementById('importBox');
const importText = document.getElementById('importText');
const importBtn = document.getElementById('importBtn');

const statPaid = document.getElementById('statPaid');
const statUnpaid = document.getElementById('statUnpaid');
const statCount = document.getElementById('statCount');

// ---------- Helpers ----------
function currency(n) {
  return '₹' + (Number(n) || 0).toLocaleString('en-IN', { maximumFractionDigits: 2 });
}
const num = (v) => Number(v) || 0;
const r2 = (v) => Math.round(v * 100) / 100;

function invoiceNo(id) { return `INV-${String(id).padStart(4, '0')}`; }
function balanceOf(inv) {
  if (inv.status === 'Cancelled') return 0;
  return Math.max(0, num(inv.amount) - num(inv.insuranceCovered) - num(inv.amountPaid));
}
function pendingOf(inv) { return inv.status === 'Cancelled' ? 0 : num(inv.coveragePending); }
function parseItems(inv) {
  try {
    const items = JSON.parse(inv.itemsJson || '[]');
    if (Array.isArray(items) && items.length) return items;
  } catch (e) { /* fall through */ }
  return [{ category: 'Miscellaneous', desc: inv.description || 'Charge', qty: 1, amount: num(inv.amount), taxPct: 0 }];
}
function showToast(message) {
  toastMessage.textContent = message;
  toast.classList.add('is-visible');
  setTimeout(() => toast.classList.remove('is-visible'), 2800);
}
function fmtDate(d) {
  return d ? new Date(d + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }) : '—';
}

// ---------- Load ----------
async function loadAll() {
  try {
    const results = await Promise.all([
      LifetagAPI.getBillings(), LifetagAPI.getPatients(), LifetagAPI.getDoctors(), LifetagAPI.getBillingCategories()
    ]);
    [invoices, allPatients, allDoctors, categories] = results;
    patientSelect.innerHTML = '<option value="">Select patient</option>';
    allPatients.slice().sort((a, b) => a.name.localeCompare(b.name)).forEach(p => {
      const opt = document.createElement('option');
      opt.value = p.id;
      opt.textContent = `${p.name} (LT-${String(p.id).padStart(4, '0')})`;
      patientSelect.appendChild(opt);
    });
    doctorSelect.innerHTML = '<option value="">— none —</option>';
    allDoctors.forEach(d => {
      const opt = document.createElement('option');
      opt.value = d.id;
      opt.textContent = `${d.name} · ${d.specialization || ''}`;
      doctorSelect.appendChild(opt);
    });
    renderInvoices();
  } catch (err) {
    tableBody.innerHTML = '';
    emptyState.style.display = 'block';
    emptyState.textContent = `Couldn't load invoices: ${err.message}`;
  }
}

// ---------- Stats ----------
function renderStats() {
  let collected = 0;
  let outstanding = 0;
  invoices.forEach(inv => {
    if (inv.status === 'Cancelled') return;
    collected += num(inv.amountPaid);
    outstanding += balanceOf(inv);
  });
  statPaid.textContent = currency(collected);
  statUnpaid.textContent = currency(outstanding);
  statCount.textContent = invoices.length;
}

// ---------- Render table ----------
function renderInvoices() {
  const query = searchInput.value.trim().toLowerCase();
  const statusValue = statusFilter.value;

  const filtered = invoices.filter(inv => {
    const patientName = (inv.patient && inv.patient.name) || '';
    const matchesQuery = patientName.toLowerCase().includes(query) || String(inv.id).includes(query) || invoiceNo(inv.id).toLowerCase().includes(query);
    const matchesStatus = !statusValue || inv.status === statusValue || (statusValue === 'Unpaid' && inv.status === 'Pending');
    return matchesQuery && matchesStatus;
  }).sort((a, b) => b.id - a.id);

  tableBody.innerHTML = '';
  emptyState.style.display = filtered.length === 0 ? 'block' : 'none';

  filtered.slice(0, 400).forEach(inv => {
    const patientName = (inv.patient && inv.patient.name) || 'Unknown patient';
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><a href="invoice.html?id=${encodeURIComponent(inv.id)}" style="font-family:var(--font-mono); font-size:12.5px; color:var(--teal-500); font-weight:600;">${escapeHtml(invoiceNo(inv.id))}</a></td>
      <td>${escapeHtml(patientName)}</td>
      <td>${escapeHtml(fmtDate(inv.billDate))}</td>
      <td>${currency(inv.amount)}${num(inv.insuranceCovered) > 0 ? `<div class="cell-person__meta">Cover − ${escapeHtml(currency(inv.insuranceCovered))} · patient pays ${escapeHtml(currency(num(inv.amount) - num(inv.insuranceCovered)))}</div>` : ''}</td>
      <td>${currency(balanceOf(inv))}${pendingOf(inv) > 0 ? `<div class="cell-person__meta">${escapeHtml(currency(pendingOf(inv)))} awaiting sanction</div>` : ''}${num(inv.refundDue) > 0 ? `<div class="cell-person__meta" style="color:var(--coral-500);">Refund due ${escapeHtml(currency(inv.refundDue))}</div>` : ''}</td>
      <td><span class="status-pill ${escapeHtml((inv.status || '').toLowerCase())}">${escapeHtml(inv.status)}</span></td>
      <td>
        <div class="row-actions">
          <a class="edit-btn" href="invoice.html?id=${encodeURIComponent(inv.id)}" aria-label="View invoice" title="View / print invoice" style="display:inline-flex;align-items:center;justify-content:center;">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7Z"/><circle cx="12" cy="12" r="3"/></svg>
          </a>
          <button class="edit-btn" data-act="edit" data-id="${escapeHtml(inv.id)}" aria-label="Edit">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>
          </button>
          <button class="delete-btn" data-act="delete" data-id="${escapeHtml(inv.id)}" aria-label="Delete">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>
          </button>
        </div>
      </td>
    `;
    tableBody.appendChild(tr);
  });
  if (filtered.length > 400) {
    const tr = document.createElement('tr');
    tr.innerHTML = `<td colspan="7" style="text-align:center; color:var(--text-2); font-size:12.5px;">Showing the latest 400 of ${filtered.length} invoices — use search or the status filter to narrow down.</td>`;
    tableBody.appendChild(tr);
  }
  renderStats();
}

// ---------- Line items (inside modal) ----------
function addItemRow(item = {}) {
  const row = document.createElement('div');
  row.className = 'item-row item-row--rich';
  if (item.ref) row.dataset.ref = JSON.stringify(item.ref);
  const locked = !!item.auto;
  if (locked) row.dataset.auto = item.auto;
  const cats = categories.map(c => `<option value="${escapeHtml(c)}" ${c === (item.category || 'Doctor Fees') ? 'selected' : ''}>${escapeHtml(c)}</option>`).join('');
  row.innerHTML = `
    <div class="field"><label>Category${locked ? ' <span style="color:var(--teal-500); font-weight:600;">· auto</span>' : ''}</label><select class="item-cat" ${locked ? 'disabled' : ''}>${cats}</select></div>
    <div class="field"><label>Description</label><input type="text" class="item-desc" value="${escapeHtml(item.desc || '')}" placeholder="e.g. Consultation fee" required maxlength="200" ${locked ? 'readonly' : ''}></div>
    <div class="field"><label>Qty</label><input type="number" class="item-qty" value="${escapeHtml(item.qty ?? 1)}" min="1" step="1" required ${locked ? 'readonly' : ''}></div>
    <div class="field"><label>Rate (₹)</label><input type="number" class="item-amount" value="${escapeHtml(item.amount ?? 0)}" min="0" step="0.01" required ${locked ? 'readonly' : ''}></div>
    <div class="field"><label>GST %</label><input type="number" class="item-tax" value="${escapeHtml(item.taxPct ?? 0)}" min="0" max="28" step="1" ${locked ? 'readonly' : ''}></div>
    <button type="button" class="remove-item-btn" aria-label="Remove charge" ${locked ? 'disabled title="Added automatically from the admission record" style="visibility:hidden"' : ''}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>
    </button>`;
  itemsContainer.appendChild(row);
  row.querySelector('.remove-item-btn').addEventListener('click', () => { row.remove(); updateSummary(); });
  row.querySelectorAll('input').forEach(i => i.addEventListener('input', updateSummary));
}

function readItems() {
  return Array.from(itemsContainer.querySelectorAll('.item-row')).map(row => {
    const it = {
      category: row.querySelector('.item-cat').value,
      desc: row.querySelector('.item-desc').value.trim(),
      qty: num(row.querySelector('.item-qty').value),
      amount: num(row.querySelector('.item-amount').value),
      taxPct: num(row.querySelector('.item-tax').value)
    };
    if (row.dataset.ref) it.ref = JSON.parse(row.dataset.ref);
    if (row.dataset.auto) it.auto = row.dataset.auto;
    return it;
  });
}

function updateSummary() {
  let sub = 0, tax = 0;
  readItems().forEach(it => {
    const line = r2(it.qty * it.amount);
    sub += line;
    tax += r2(line * it.taxPct / 100);
  });
  const discount = num(document.getElementById('billDiscount').value);
  const cover = editingInvoice ? num(editingInvoice.insuranceCovered) : 0;
  const pending = editingInvoice ? num(editingInvoice.coveragePending) : 0;
  const total = Math.max(0, r2(sub - discount + tax));
  document.getElementById('sumSubtotal').textContent = currency(sub);
  document.getElementById('sumDiscount').textContent = '− ' + currency(discount);
  document.getElementById('sumTax').textContent = currency(tax);
  document.getElementById('billTotalDisplay').textContent = currency(total);
  document.getElementById('sumCover').textContent = '− ' + currency(cover);
  document.getElementById('sumPayable').textContent = currency(Math.max(0, total - cover));
  document.getElementById('sumPendingRow').style.display = pending > 0 ? '' : 'none';
  document.getElementById('sumPending').textContent = currency(pending);
}

document.getElementById('addItemBtn').addEventListener('click', () => { addItemRow(); updateSummary(); });
document.getElementById('billDiscount').addEventListener('input', updateSummary);

// ---------- Import un-invoiced charges ----------
let suggestion = null;
async function checkSuggestions() {
  suggestion = null;
  importBtn.style.display = 'none';
  const pid = Number(patientSelect.value);
  if (!pid || editingId) { importBox.style.display = 'none'; return; }
  importBox.style.display = 'flex';
  importText.textContent = "Checking the patient's record for un-invoiced charges…";
  try {
    const res = await LifetagAPI.getSuggestedCharges(pid);
    const fresh = (res.items || []).filter(it => !it.ref || !importedRefs.some(r => r.type === it.ref.type && r.id === it.ref.id));
    if (!fresh.length) { importText.textContent = 'Nothing waiting to be invoiced for this patient.'; return; }
    suggestion = { ...res, items: fresh };
    const total = fresh.reduce((s, it) => s + it.qty * it.amount, 0);
    importText.innerHTML = `<b>${fresh.length} charge${fresh.length === 1 ? '' : 's'}</b> from the clinical record are not invoiced yet (about ${escapeHtml(currency(total))}): consultations, bed days, scans / lab tests, procedures, medicines and blood issued.`;
    importBtn.style.display = '';
  } catch (err) {
    importText.textContent = `Couldn't check the record: ${err.message}`;
  }
}
patientSelect.addEventListener('change', () => {
  importedRefs = [];
  importedAdmissionId = null;
  checkSuggestions();
});
importBtn.addEventListener('click', () => {
  if (!suggestion) return;
  // drop the empty starter row, if untouched
  itemsContainer.querySelectorAll('.item-row').forEach(r => {
    if (!r.querySelector('.item-desc').value.trim() && !num(r.querySelector('.item-amount').value)) r.remove();
  });
  suggestion.items.forEach(it => {
    addItemRow(it);
    if (it.ref) importedRefs.push(it.ref);
  });
  if (suggestion.doctorId && !doctorSelect.value) doctorSelect.value = suggestion.doctorId;
  if (suggestion.admissionId) importedAdmissionId = suggestion.admissionId;
  updateSummary();
  showToast(`${suggestion.items.length} charges imported — review the rates before saving.`);
  suggestion = null;
  importBtn.style.display = 'none';
  importText.textContent = 'Charges imported. Edit any rate or quantity before saving.';
});

// ---------- Modal open/close ----------
function openModal(mode, invoice) {
  form.reset();
  itemsContainer.innerHTML = '';
  importedRefs = [];
  importedAdmissionId = null;
  suggestion = null;
  importBox.style.display = 'none';
  const editing = mode === 'edit' && invoice;
  editingInvoice = editing ? invoice : null;
  renderCoverInfo(editingInvoice);
  document.getElementById('paidNowField').style.display = editing ? 'none' : '';
  document.getElementById('payMethodField').style.display = editing ? 'none' : '';
  document.getElementById('paidHint').style.display = editing ? 'block' : 'none';

  if (editing) {
    editingId = invoice.id;
    modalTitle.textContent = `Edit Invoice ${invoiceNo(invoice.id)}`;
    patientSelect.value = invoice.patient ? invoice.patient.id : '';
    patientSelect.disabled = true;
    doctorSelect.value = invoice.doctor ? invoice.doctor.id : '';
    document.getElementById('billDate').value = invoice.billDate || '';
    document.getElementById('billDueDate').value = invoice.dueDate || '';
    document.getElementById('billDiscount').value = num(invoice.discount);
    document.getElementById('billNotes').value = invoice.notes || '';
    document.getElementById('billStatus').value = invoice.status === 'Cancelled' ? 'Cancelled' : '';
    parseItems(invoice).forEach(it => addItemRow(it));
  } else {
    editingId = null;
    patientSelect.disabled = false;
    modalTitle.textContent = 'New Invoice';
    const today = new Date();
    document.getElementById('billDate').value = today.toISOString().slice(0, 10);
    const due = new Date(today.getTime() + 15 * 86400000);
    document.getElementById('billDueDate').value = due.toISOString().slice(0, 10);
    addItemRow({ category: 'Doctor Fees' });
  }
  updateSummary();
  overlay.classList.add('is-open');
}

function closeModal() { overlay.classList.remove('is-open'); editingInvoice = null; }

// Read-only picture of what insurers / schemes have sanctioned (deducted) and what is still awaiting sanction.
function renderCoverInfo(inv) {
  const box = document.getElementById('coverInfo');
  if (!box) return;
  if (!inv) {
    box.innerHTML = 'Cover is applied automatically, and only after an insurer or scheme has <b>sanctioned</b> a claim. File and track claims from the Insurance / Schemes screens.';
    return;
  }
  let lines = [];
  try { lines = JSON.parse(inv.coverageJson || '[]'); } catch (e) { lines = []; }
  if (!lines.length) {
    box.innerHTML = 'No insurance or scheme claim is filed against this invoice. The patient pays the full amount.';
    return;
  }
  box.innerHTML = lines.map(l => {
    const sanctioned = num(l.deducted) > 0;
    return `<div>${escapeHtml(l.kind === 'SCHEME' ? 'Scheme' : 'Insurance')}: <b>${escapeHtml(l.source)}</b> — ${escapeHtml(l.status)}`
      + ` · claimed ${escapeHtml(currency(l.claimed))}`
      + (sanctioned ? ` · <b style="color:var(--teal-500);">sanctioned − ${escapeHtml(currency(l.deducted))}</b>` : ' · <span style="color:var(--gold-500, #B7791F);">not deducted (not sanctioned)</span>')
      + '</div>';
  }).join('') + (num(inv.refundDue) > 0 ? `<div style="color:var(--coral-500); margin-top:4px;">Patient has paid ${escapeHtml(currency(inv.refundDue))} more than they owe after cover — refund or adjust.</div>` : '');
}

document.getElementById('openAddModal').addEventListener('click', () => openModal('add'));
document.getElementById('closeModal').addEventListener('click', closeModal);
document.getElementById('cancelModal').addEventListener('click', closeModal);
overlay.addEventListener('click', (e) => { if (e.target === overlay) closeModal(); });

// ---------- Save ----------
form.addEventListener('submit', async (e) => {
  e.preventDefault();
  const submitBtn = form.querySelector('button[type="submit"]');
  const allItems = readItems();
  if (allItems.length === 0) { alert('Add at least one charge before saving.'); return; }
  // lines marked "auto" (bed days, nursing, visits, blood, tests, treatments) are rebuilt by the server from the admission record
  const items = allItems.filter(i => !i.auto);

  const patientId = Number(patientSelect.value);
  const patientName = (allPatients.find(p => p.id === patientId) || {}).name || 'patient';
  const cancelled = document.getElementById('billStatus').value === 'Cancelled';

  const data = {
    patientId,
    doctorId: doctorSelect.value ? Number(doctorSelect.value) : null,
    billDate: document.getElementById('billDate').value,
    dueDate: document.getElementById('billDueDate').value,
    itemsJson: JSON.stringify(items.map(({ ref, ...rest }) => rest)),
    discount: num(document.getElementById('billDiscount').value),
    notes: document.getElementById('billNotes').value.trim(),
    status: cancelled ? 'Cancelled' : 'Unpaid'
  };
  if (importedAdmissionId) data.admissionId = importedAdmissionId;
  const refs = items.map(i => i.ref).filter(Boolean);
  if (refs.length) data.sourceRefs = refs;
  if (!editingId) {
    data.amountPaid = num(document.getElementById('billPaidNow').value);
    data.paymentMethod = document.getElementById('billPayMethod').value;
  }

  submitBtn.disabled = true;
  try {
    if (editingId) {
      await LifetagAPI.updateBilling(editingId, data);
      showToast('Invoice updated.');
      logActivity('Updated', 'Invoice', `${invoiceNo(editingId)} — ${patientName}`);
    } else {
      const created = await LifetagAPI.createBilling(data);
      showToast('New invoice created.');
      logActivity('Created', 'Invoice', `${invoiceNo(created.id)} — ${patientName}`);
    }
    closeModal();
    await loadAll();
  } catch (err) {
    showToast(`Save failed: ${err.message}`);
  } finally {
    submitBtn.disabled = false;
  }
});

// ---------- Edit / Delete ----------
tableBody.addEventListener('click', async (e) => {
  const btn = e.target.closest('button[data-act]');
  if (!btn) return;
  const id = Number(btn.dataset.id);
  if (btn.dataset.act === 'edit') {
    openModal('edit', invoices.find(inv => inv.id === id));
  } else if (btn.dataset.act === 'delete') {
    if (confirm(`Delete invoice ${invoiceNo(id)}?`)) {
      try {
        await LifetagAPI.deleteBilling(id);
        showToast('Invoice deleted.');
        logActivity('Deleted', 'Invoice', invoiceNo(id));
        await loadAll();
      } catch (err) {
        showToast(`Delete failed: ${err.message}`);
      }
    }
  }
});

// ---------- Search & filter ----------
searchInput.addEventListener('input', renderInvoices);
statusFilter.addEventListener('change', renderInvoices);

// ---------- Initial load ----------
loadAll();
