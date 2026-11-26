// ============================================
// PATIENT PORTAL — live data via LifetagAPI (see js/api-config.js)
// Covers: patient-dashboard.html, patient-appointments.html,
//         patient-billing.html, patient-feedback.html
//
// "Who am I" is resolved from the real authenticated session via
// GET /api/patients/me (see PatientController#getMyProfile) — never from
// whatever was typed on the login screen. Every list below is fetched
// scoped to that real patient id, so a patient only ever sees their own
// appointments/bills/feedback, and only ever books with doctors that
// actually exist in the system.
// ============================================

let myProfile = null;
let myAppointments = [];
let myInvoices = [];
let myFeedback = [];
let allDoctors = [];

// ---------- Shared helpers ----------
function currency(n) { return '₹' + (Number(n) || 0).toLocaleString('en-IN'); }

function parseItems(inv) {
  try {
    const items = JSON.parse(inv.itemsJson || '[]');
    return Array.isArray(items) && items.length ? items : [{ desc: inv.description || 'Charge', qty: 1, amount: Number(inv.amount) || 0 }];
  } catch (e) {
    return [{ desc: inv.description || 'Charge', qty: 1, amount: Number(inv.amount) || 0 }];
  }
}

function invoiceTotal(inv) { return Number(inv.amount) || 0; }
/** What the patient still has to pay: bill - sanctioned insurance/scheme cover - already paid. */
function invoiceBalance(inv) {
  if (inv.status === 'Cancelled') return 0;
  return Math.max(0, invoiceTotal(inv) - (Number(inv.insuranceCovered) || 0) - (Number(inv.amountPaid) || 0));
}

// ============================================
// BOOT — resolve the real logged-in patient, then wire up whichever
// page we're actually on.
// ============================================
(async function initPatientPortal() {
  try {
    myProfile = await LifetagAPI.getMyPatientProfile();
  } catch (err) {
    const header = document.querySelector('.content__header');
    if (header) {
      header.innerHTML = `<h1>Unable to load your profile</h1><p>${escapeHtml(err.message)}</p>`;
    }
    console.error('Failed to resolve current patient:', err);
    return;
  }

  // Now that we know who's really logged in, show their real Patient ID
  // (dashboard.js already painted the name/initials generically).
  const idLabelEl = document.getElementById('patientIdLabel');
  if (idLabelEl) idLabelEl.textContent = `Patient ID: LT-${String(myProfile.id).padStart(4, '0')}`;

  await initDashboardPage();
  await initAppointmentsPage();
  await initBillingPage();
  await initFeedbackPage();
})();

// ============================================
// DASHBOARD PAGE
// ============================================
async function initDashboardPage() {
  const greetingEl = document.getElementById('greeting');
  if (!greetingEl || !document.getElementById('statNextAppt')) return;

  const emptyState = document.getElementById('emptyState');
  try {
    [myAppointments, myInvoices] = await Promise.all([
      LifetagAPI.getAppointmentsByPatient(myProfile.id),
      LifetagAPI.getBillingsByPatient(myProfile.id)
    ]);
  } catch (err) {
    if (emptyState) { emptyState.style.display = 'block'; emptyState.textContent = `Couldn't load your data: ${err.message}`; }
    return;
  }

  const upcoming = myAppointments
    .filter(a => a.status !== 'Completed' && a.status !== 'Cancelled')
    .sort((a, b) => `${a.appointmentDate || ''}${a.appointmentTime || ''}`.localeCompare(`${b.appointmentDate || ''}${b.appointmentTime || ''}`));

  document.getElementById('statNextAppt').textContent = upcoming.length
    ? new Date(upcoming[0].appointmentDate + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })
      + ' · ' + (upcoming[0].appointmentTime || '').toString().slice(0, 5)
    : 'None scheduled';

  const outstanding = myInvoices.reduce((s, i) => s + invoiceBalance(i), 0);
  document.getElementById('statOutstanding').textContent = currency(outstanding);
  document.getElementById('statBloodGroup').textContent = myProfile.bloodGroup || '—';

  const body = document.getElementById('upcomingApptBody');
  body.innerHTML = '';
  if (upcoming.length === 0) {
    emptyState.style.display = 'block';
  } else {
    emptyState.style.display = 'none';
    upcoming.forEach(a => {
      const doctorName = (a.doctor && a.doctor.name) || 'Unknown doctor';
      const dateStr = a.appointmentDate
        ? new Date(a.appointmentDate + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })
        : '—';
      const time = (a.appointmentTime || '').toString().slice(0, 5);
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td>${escapeHtml(doctorName)}</td>
        <td>${escapeHtml(dateStr)} &middot; ${escapeHtml(time)}</td>
        <td>${a.reason ? escapeHtml(a.reason) : '&mdash;'}</td>
        <td><span class="status-pill ${escapeHtml((a.status || '').toLowerCase())}">${escapeHtml(a.status)}</span></td>
      `;
      body.appendChild(tr);
    });
  }
}

// ============================================
// MY APPOINTMENTS PAGE
// ============================================
async function initAppointmentsPage() {
  const apptTableBody = document.getElementById('apptTableBody');
  if (!apptTableBody) return;

  const searchInput = document.getElementById('apptSearch');
  const statusFilter = document.getElementById('statusFilter');
  const emptyState = document.getElementById('emptyState');
  const overlay = document.getElementById('apptModalOverlay');
  const form = document.getElementById('apptForm');
  const doctorSelect = document.getElementById('doctorSelect');
  const toast = document.getElementById('toast');
  const toastMessage = document.getElementById('toastMessage');

  function showToast(msg) {
    toastMessage.textContent = msg;
    toast.classList.add('is-visible');
    setTimeout(() => toast.classList.remove('is-visible'), 2400);
  }

  try {
    [myAppointments, allDoctors] = await Promise.all([
      LifetagAPI.getAppointmentsByPatient(myProfile.id),
      LifetagAPI.getDoctors()   // real, currently-registered doctors — never a fixed demo list
    ]);
  } catch (err) {
    emptyState.style.display = 'block';
    emptyState.textContent = `Couldn't load appointments: ${err.message}`;
    return;
  }

  doctorSelect.innerHTML = '<option value="">Select doctor</option>';
  allDoctors.forEach(d => {
    const opt = document.createElement('option');
    opt.value = d.id;
    opt.textContent = `${d.name} · ${d.specialization || 'General'}`;
    doctorSelect.appendChild(opt);
  });

  function renderAppts() {
    const query = searchInput.value.trim().toLowerCase();
    const statusValue = statusFilter.value;
    const filtered = myAppointments.filter(a => {
      const doctorName = (a.doctor && a.doctor.name) || '';
      const matchesQuery = doctorName.toLowerCase().includes(query);
      const matchesStatus = !statusValue || a.status === statusValue;
      return matchesQuery && matchesStatus;
    }).sort((a, b) => `${a.appointmentDate || ''}${a.appointmentTime || ''}`.localeCompare(`${b.appointmentDate || ''}${b.appointmentTime || ''}`));

    apptTableBody.innerHTML = '';
    emptyState.style.display = filtered.length === 0 ? 'block' : 'none';

    filtered.forEach(a => {
      const doctorName = (a.doctor && a.doctor.name) || 'Unknown doctor';
      const dateStr = a.appointmentDate
        ? new Date(a.appointmentDate + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })
        : '—';
      const time = (a.appointmentTime || '').toString().slice(0, 5);
      const canCancel = a.status !== 'Completed' && a.status !== 'Cancelled';
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td>${escapeHtml(doctorName)}</td>
        <td>${escapeHtml(dateStr)} &middot; ${escapeHtml(time)}</td>
        <td>${a.reason ? escapeHtml(a.reason) : '&mdash;'}</td>
        <td><span class="status-pill ${escapeHtml((a.status || '').toLowerCase())}">${escapeHtml(a.status)}</span></td>
        <td>
          ${canCancel ? `
          <div class="row-actions">
            <button class="delete-btn cancel-btn" data-id="${escapeHtml(a.id)}" aria-label="Cancel">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6L6 18M6 6l12 12"/></svg>
            </button>
          </div>` : '&mdash;'}
        </td>
      `;
      apptTableBody.appendChild(tr);
    });
  }

  apptTableBody.addEventListener('click', async (e) => {
    const btn = e.target.closest('.cancel-btn');
    if (!btn) return;
    const appt = myAppointments.find(a => a.id === Number(btn.dataset.id));
    const doctorName = (appt.doctor && appt.doctor.name) || 'your doctor';
    if (!confirm(`Cancel your appointment with ${doctorName} on ${appt.appointmentDate}?`)) return;
    try {
      await LifetagAPI.updateAppointment(appt.id, { status: 'Cancelled' });
      appt.status = 'Cancelled';
      renderAppts();
      showToast('Appointment cancelled.');
      logActivity('Updated', 'Appointment', `Cancelled with ${doctorName}`);
    } catch (err) {
      showToast(`Cancel failed: ${err.message}`);
    }
  });

  document.getElementById('openAddModal').addEventListener('click', () => {
    form.reset();
    overlay.classList.add('is-open');
  });
  document.getElementById('closeModal').addEventListener('click', () => overlay.classList.remove('is-open'));
  document.getElementById('cancelModal').addEventListener('click', () => overlay.classList.remove('is-open'));
  overlay.addEventListener('click', (e) => { if (e.target === overlay) overlay.classList.remove('is-open'); });

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const submitBtn = form.querySelector('button[type="submit"]');
    const doctorId = Number(doctorSelect.value);
    const doctorName = (allDoctors.find(d => d.id === doctorId) || {}).name || 'the doctor';

    const data = {
      patientId: myProfile.id,
      doctorId,
      appointmentDate: document.getElementById('apptDate').value,
      appointmentTime: document.getElementById('apptTime').value,
      reason: document.getElementById('reason').value.trim(),
      status: 'Pending'
    };

    submitBtn.disabled = true;
    try {
      // Real POST — this is what makes the request actually visible to the
      // assigned doctor and Reception (they read the same /api/appointments
      // data this writes to; previously this only pushed to a local array).
      await LifetagAPI.createAppointment(data);
      overlay.classList.remove('is-open');
      showToast('Appointment requested — awaiting confirmation.');
      logActivity('Created', 'Appointment', `Requested with ${doctorName}`);
      myAppointments = await LifetagAPI.getAppointmentsByPatient(myProfile.id);
      renderAppts();
    } catch (err) {
      showToast(`Request failed: ${err.message}`);
    } finally {
      submitBtn.disabled = false;
    }
  });

  searchInput.addEventListener('input', renderAppts);
  statusFilter.addEventListener('change', renderAppts);
  renderAppts();
}

// ============================================
// MY BILLS PAGE
// ============================================
async function initBillingPage() {
  const billTableBody = document.getElementById('billTableBody');
  if (!billTableBody) return;

  const emptyState = document.getElementById('emptyState');
  const overlay = document.getElementById('viewModalOverlay');
  const detailBody = document.getElementById('invoiceDetailBody');

  try {
    myInvoices = await LifetagAPI.getBillingsByPatient(myProfile.id);
  } catch (err) {
    emptyState.style.display = 'block';
    emptyState.textContent = `Couldn't load bills: ${err.message}`;
    return;
  }

  const paidTotal = myInvoices.reduce((s, i) => s + (Number(i.amountPaid) || 0), 0);
  const balanceOf = invoiceBalance;
  const dueTotal = myInvoices.reduce((s, i) => s + balanceOf(i), 0);
  document.getElementById('statPaid').textContent = currency(paidTotal);
  document.getElementById('statDue').textContent = currency(dueTotal);

  billTableBody.innerHTML = '';
  emptyState.style.display = myInvoices.length === 0 ? 'block' : 'none';

  myInvoices.forEach(inv => {
    const tr = document.createElement('tr');
    const dateStr = inv.billDate
      ? new Date(inv.billDate + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })
      : '—';
    tr.innerHTML = `
      <td><span style="font-family:var(--font-mono); font-size:12.5px;">INV-${escapeHtml(String(inv.id).padStart(4, '0'))}</span></td>
      <td>${escapeHtml(dateStr)}</td>
      <td>${currency(invoiceTotal(inv))}${Number(inv.insuranceCovered) > 0 ? `<div class="cell-person__meta">Insurance / scheme − ${currency(inv.insuranceCovered)} · you pay ${currency(invoiceTotal(inv) - Number(inv.insuranceCovered))}</div>` : ''}${balanceOf(inv) > 0 && inv.status !== 'Cancelled' ? `<div class="cell-person__meta">Balance ${currency(balanceOf(inv))}</div>` : ''}${Number(inv.coveragePending) > 0 && inv.status !== 'Cancelled' ? `<div class="cell-person__meta">${currency(inv.coveragePending)} awaiting insurer / scheme sanction</div>` : ''}${Number(inv.refundDue) > 0 ? `<div class="cell-person__meta">Refund due to you ${currency(inv.refundDue)}</div>` : ''}</td>
      <td><span class="status-pill ${escapeHtml((inv.status || '').toLowerCase())}">${escapeHtml(inv.status)}</span></td>
      <td>
        <div class="row-actions">
          <a class="btn-add" style="text-decoration:none;padding:6px 12px;font-size:12px" href="invoice.html?id=${escapeHtml(inv.id)}">View invoice</a>
          <button class="edit-btn view-btn" data-id="${escapeHtml(inv.id)}" aria-label="View">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7Z"/><circle cx="12" cy="12" r="3"/></svg>
          </button>
        </div>
      </td>
    `;
    billTableBody.appendChild(tr);
  });

  billTableBody.addEventListener('click', (e) => {
    const btn = e.target.closest('.view-btn');
    if (!btn) return;
    const inv = myInvoices.find(i => i.id === Number(btn.dataset.id));
    const items = parseItems(inv);
    document.getElementById('modalTitle').textContent = `Invoice INV-${String(inv.id).padStart(4, '0')}`;
    detailBody.innerHTML = `
      <table class="data-table" style="width:100%;">
        <thead><tr><th>Description</th><th>Qty</th><th>Amount</th></tr></thead>
        <tbody>
          ${items.map(it => `<tr><td>${escapeHtml(it.desc)}</td><td>${escapeHtml(it.qty)}</td><td>${currency(it.qty * it.amount)}</td></tr>`).join('')}
        </tbody>
      </table>
      <div class="bill-total-row"><span>Total</span><span>${currency(invoiceTotal(inv))}</span></div>
      ${Number(inv.insuranceCovered) > 0 ? `<div class="bill-total-row"><span>Insurance / scheme (sanctioned)</span><span>− ${currency(inv.insuranceCovered)}</span></div>
      <div class="bill-total-row"><span>You pay</span><span>${currency(invoiceTotal(inv) - Number(inv.insuranceCovered))}</span></div>` : ''}
      <div class="bill-total-row"><span>Paid</span><span>− ${currency(inv.amountPaid)}</span></div>
      <div class="bill-total-row"><span>Balance due</span><span>${currency(balanceOf(inv))}</span></div>
    `;
    overlay.classList.add('is-open');
  });

  document.getElementById('closeModal').addEventListener('click', () => overlay.classList.remove('is-open'));
  document.getElementById('cancelModal').addEventListener('click', () => overlay.classList.remove('is-open'));
  overlay.addEventListener('click', (e) => { if (e.target === overlay) overlay.classList.remove('is-open'); });
}

// ============================================
// FEEDBACK PAGE
// ============================================
async function initFeedbackPage() {
  const fbForm = document.getElementById('fbForm');
  if (!fbForm) return;

  const fbDoctor = document.getElementById('fbDoctor');
  const ratingPicker = document.getElementById('ratingPicker');
  const fbRatingInput = document.getElementById('fbRating');
  const formNote = document.getElementById('formNote');
  const myFeedbackBody = document.getElementById('myFeedbackBody');
  const emptyState = document.getElementById('emptyState');

  const STAR_FILLED = `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87L18.18 21 12 17.77 5.82 21 7 14.14l-5-4.87 6.91-1.01L12 2z"/></svg>`;
  const STAR_EMPTY = `<svg class="empty" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87L18.18 21 12 17.77 5.82 21 7 14.14l-5-4.87 6.91-1.01L12 2z"/></svg>`;
  let selectedRating = 5;

  let allFeedback = [];
  try {
    [allDoctors, allFeedback] = await Promise.all([
      allDoctors.length ? Promise.resolve(allDoctors) : LifetagAPI.getDoctors(),
      LifetagAPI.getFeedback()
    ]);
  } catch (err) {
    emptyState.style.display = 'block';
    emptyState.textContent = `Couldn't load feedback: ${err.message}`;
    return;
  }
  myFeedback = allFeedback.filter(f => f.patient && f.patient.id === myProfile.id);

  fbDoctor.innerHTML = '<option value="">Select doctor</option>';
  allDoctors.forEach(d => {
    const opt = document.createElement('option');
    opt.value = d.id;
    opt.textContent = `${d.name} · ${d.specialization || 'General'}`;
    fbDoctor.appendChild(opt);
  });

  function renderPicker() {
    ratingPicker.innerHTML = '';
    for (let i = 1; i <= 5; i++) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = i <= selectedRating ? 'is-filled' : '';
      btn.innerHTML = i <= selectedRating ? STAR_FILLED : STAR_EMPTY;
      btn.addEventListener('click', () => { selectedRating = i; fbRatingInput.value = i; renderPicker(); });
      ratingPicker.appendChild(btn);
    }
  }
  renderPicker();

  function renderMyFeedback() {
    myFeedbackBody.innerHTML = '';
    emptyState.style.display = myFeedback.length === 0 ? 'block' : 'none';
    myFeedback.forEach(f => {
      const stars = Array.from({ length: 5 }, (_, i) => i < (f.rating || 0) ? STAR_FILLED : STAR_EMPTY).join('');
      const doctorName = (f.doctor && f.doctor.name) || 'Unknown doctor';
      const dateStr = f.feedbackDate
        ? new Date(f.feedbackDate + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })
        : '—';
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td>${escapeHtml(doctorName)}</td>
        <td><div class="star-rating">${stars}</div></td>
        <td style="max-width:260px;">${f.comments ? escapeHtml(f.comments) : '&mdash;'}</td>
        <td>${escapeHtml(dateStr)}</td>
      `;
      myFeedbackBody.appendChild(tr);
    });
  }
  renderMyFeedback();

  fbForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const submitBtn = fbForm.querySelector('button[type="submit"]');
    const doctorId = Number(fbDoctor.value);

    const data = {
      patientId: myProfile.id,
      doctorId,
      rating: Number(fbRatingInput.value),
      comments: document.getElementById('fbComment').value.trim()
    };

    if (submitBtn) submitBtn.disabled = true;
    try {
      await LifetagAPI.createFeedback(data);
      logActivity('Created', 'Feedback', `For ${(allDoctors.find(d => d.id === doctorId) || {}).name || 'doctor'}`);
      const allFeedbackNow = await LifetagAPI.getFeedback();
      myFeedback = allFeedbackNow.filter(f => f.patient && f.patient.id === myProfile.id);
      renderMyFeedback();
      fbForm.reset();
      selectedRating = 5;
      fbRatingInput.value = 5;
      renderPicker();
      formNote.textContent = 'Thanks — your feedback has been submitted.';
      formNote.className = 'form-note is-success';
      setTimeout(() => { formNote.textContent = ''; formNote.className = 'form-note'; }, 3000);
    } catch (err) {
      formNote.textContent = `Submission failed: ${err.message}`;
      formNote.className = 'form-note is-error';
    } finally {
      if (submitBtn) submitBtn.disabled = false;
    }
  });
}
