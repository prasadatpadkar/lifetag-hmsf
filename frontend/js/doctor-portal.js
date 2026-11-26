// ============================================
// DOCTOR PORTAL — live data via LifetagAPI (see js/api-config.js)
// Covers: doctor-dashboard.html, doctor-patients.html, doctor-appointments.html
// (doctor-feedback.html has its own file, js/doctor-feedback.js)
//
// "Who am I" is resolved from the real authenticated session via
// GET /api/doctors/me (see DoctorController#getMyProfile) — never from
// whatever was typed on the login screen. There's no direct doctor→patient
// assignment field, so "My Patients" is derived from this doctor's real
// appointments (the distinct patients booked with them) rather than a
// fixed list, and appointment status changes are real PUTs — which is what
// makes a status update here actually show up for the patient too.
// ============================================

let myDoctorProfile = null;
let myAppointments = [];
let myPatients = [];

function initials(name) {
  return (name || '?').replace(/^Dr\.\s*/i, '').split(' ').filter(Boolean).map(w => w[0]).join('').slice(0, 2).toUpperCase();
}

// ============================================
// BOOT — resolve the real logged-in doctor, then wire up whichever
// page we're actually on.
// ============================================
(async function initDoctorPortal() {
  try {
    myDoctorProfile = await LifetagAPI.getMyDoctorProfile();
  } catch (err) {
    const header = document.querySelector('.content__header');
    if (header) {
      header.innerHTML = `<h1>Unable to load your profile</h1><p>${escapeHtml(err.message)}</p>`;
    }
    console.error('Failed to resolve current doctor:', err);
    return;
  }

  try {
    myAppointments = await LifetagAPI.getAppointmentsByDoctor(myDoctorProfile.id);
  } catch (err) {
    console.error('Failed to load appointments:', err);
    myAppointments = [];
  }

  // Distinct patients derived from real bookings with this doctor.
  const seen = new Set();
  myPatients = [];
  myAppointments.forEach(a => {
    if (a.patient && !seen.has(a.patient.id)) {
      seen.add(a.patient.id);
      myPatients.push(a.patient);
    }
  });

  initDashboardPage();
  initPatientsPage();
  initAppointmentsPage();
})();

// ============================================
// DASHBOARD PAGE
// ============================================
function initDashboardPage() {
  const greetingEl = document.getElementById('greeting');
  if (!greetingEl || !document.getElementById('statMyPatients')) return;

  const today = new Date().toISOString().slice(0, 10);
  const todaysAppts = myAppointments.filter(a => a.appointmentDate === today);

  document.getElementById('statMyPatients').textContent = myPatients.length;
  document.getElementById('statTodayAppts').textContent = todaysAppts.length;
  const ratingEl = document.getElementById('statRating');
  if (ratingEl) ratingEl.textContent = '—'; // see doctor-feedback.html for the real average

  const todayApptBody = document.getElementById('todayApptBody');
  const emptyState = document.getElementById('emptyState');

  todayApptBody.innerHTML = '';
  if (todaysAppts.length === 0) {
    emptyState.style.display = 'block';
  } else {
    emptyState.style.display = 'none';
    todaysAppts
      .sort((a, b) => (a.appointmentTime || '').localeCompare(b.appointmentTime || ''))
      .forEach(a => {
        const patientName = (a.patient && a.patient.name) || 'Unknown patient';
        const time = (a.appointmentTime || '').toString().slice(0, 5);
        const tr = document.createElement('tr');
        tr.innerHTML = `
          <td><div class="cell-person__name">${escapeHtml(patientName)}</div></td>
          <td>${escapeHtml(time)}</td>
          <td>${a.reason ? escapeHtml(a.reason) : '&mdash;'}</td>
          <td><span class="status-pill ${escapeHtml((a.status || '').toLowerCase())}">${escapeHtml(a.status)}</span></td>
        `;
        todayApptBody.appendChild(tr);
      });
  }
}

// ============================================
// MY PATIENTS PAGE
// ============================================
function initPatientsPage() {
  const patientTableBody = document.getElementById('patientTableBody');
  if (!patientTableBody) return;

  const searchInput = document.getElementById('patientSearch');
  const emptyState = document.getElementById('emptyState');
  const overlay = document.getElementById('viewModalOverlay');
  const detailBody = document.getElementById('patientDetailBody');

  function renderMyPatients() {
    const query = searchInput.value.trim().toLowerCase();
    const filtered = myPatients.filter(p => (p.name || '').toLowerCase().includes(query));

    patientTableBody.innerHTML = '';
    emptyState.style.display = filtered.length === 0 ? 'block' : 'none';

    filtered.forEach(p => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td>
          <div class="cell-person">
            <div class="cell-person__avatar">${escapeHtml(initials(p.name))}</div>
            <div>
              <div class="cell-person__name">${escapeHtml(p.name)}</div>
              <div class="cell-person__meta">Patient ID: LT-${escapeHtml(String(p.id).padStart(4, '0'))}</div>
            </div>
          </div>
        </td>
        <td>${escapeHtml(p.age ?? '—')} yrs &middot; ${escapeHtml(p.gender || '—')}</td>
        <td>${escapeHtml(p.contact || '—')}</td>
        <td><span class="badge-blood">${escapeHtml(p.bloodGroup || '—')}</span></td>
        <td>
          <div class="row-actions">
            <a class="btn-add" style="text-decoration:none;padding:6px 12px;font-size:12px" href="doctor-patient-record.html?id=${escapeHtml(p.id)}">Full record</a>
            <button class="edit-btn view-btn" data-id="${escapeHtml(p.id)}" aria-label="View">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7Z"/><circle cx="12" cy="12" r="3"/></svg>
            </button>
          </div>
        </td>
      `;
      patientTableBody.appendChild(tr);
    });
  }

  patientTableBody.addEventListener('click', (e) => {
    const btn = e.target.closest('.view-btn');
    if (!btn) return;
    const p = myPatients.find(pt => pt.id === Number(btn.dataset.id));
    detailBody.innerHTML = `
      <div><strong>Name:</strong> ${escapeHtml(p.name)}</div>
      <div><strong>Age / Gender:</strong> ${escapeHtml(p.age ?? '—')} &middot; ${escapeHtml(p.gender || '—')}</div>
      <div><strong>Contact:</strong> ${escapeHtml(p.contact || '—')}</div>
      <div><strong>Blood group:</strong> ${escapeHtml(p.bloodGroup || '—')}</div>
      <div><strong>Address:</strong> ${p.address ? escapeHtml(p.address) : '&mdash;'}</div>
      <div><strong>Emergency contact:</strong> ${p.emergencyContact ? escapeHtml(p.emergencyContact) : '&mdash;'}</div>
    `;
    overlay.classList.add('is-open');
  });

  document.getElementById('closeModal').addEventListener('click', () => overlay.classList.remove('is-open'));
  document.getElementById('cancelModal').addEventListener('click', () => overlay.classList.remove('is-open'));
  overlay.addEventListener('click', (e) => { if (e.target === overlay) overlay.classList.remove('is-open'); });

  searchInput.addEventListener('input', renderMyPatients);
  renderMyPatients();
}

// ============================================
// MY APPOINTMENTS PAGE
// ============================================
function initAppointmentsPage() {
  const apptTableBody = document.getElementById('apptTableBody');
  if (!apptTableBody) return;

  const searchInput = document.getElementById('apptSearch');
  const statusFilter = document.getElementById('statusFilter');
  const emptyState = document.getElementById('emptyState');
  const toast = document.getElementById('toast');
  const toastMessage = document.getElementById('toastMessage');

  function showToast(msg) {
    toastMessage.textContent = msg;
    toast.classList.add('is-visible');
    setTimeout(() => toast.classList.remove('is-visible'), 2200);
  }

  function renderMyAppointments() {
    const query = searchInput.value.trim().toLowerCase();
    const statusValue = statusFilter.value;

    const filtered = myAppointments.filter(a => {
      const patientName = (a.patient && a.patient.name) || '';
      const matchesQuery = patientName.toLowerCase().includes(query);
      const matchesStatus = !statusValue || a.status === statusValue;
      return matchesQuery && matchesStatus;
    }).sort((a, b) => `${a.appointmentDate || ''}${a.appointmentTime || ''}`.localeCompare(`${b.appointmentDate || ''}${b.appointmentTime || ''}`));

    apptTableBody.innerHTML = '';
    emptyState.style.display = filtered.length === 0 ? 'block' : 'none';

    filtered.forEach(a => {
      const patientName = (a.patient && a.patient.name) || 'Unknown patient';
      const dateStr = a.appointmentDate
        ? new Date(a.appointmentDate + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })
        : '—';
      const time = (a.appointmentTime || '').toString().slice(0, 5);
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td><div class="cell-person__name">${escapeHtml(patientName)}</div></td>
        <td>${escapeHtml(dateStr)} &middot; ${escapeHtml(time)}</td>
        <td>${a.reason ? escapeHtml(a.reason) : '&mdash;'}</td>
        <td>
          <select class="select-filter status-update" data-id="${escapeHtml(a.id)}" style="padding:6px 10px; font-size:12.5px;">
            <option value="Confirmed" ${a.status === 'Confirmed' ? 'selected' : ''}>Confirmed</option>
            <option value="Pending" ${a.status === 'Pending' ? 'selected' : ''}>Pending</option>
            <option value="Waiting" ${a.status === 'Waiting' ? 'selected' : ''}>Waiting</option>
            <option value="Completed" ${a.status === 'Completed' ? 'selected' : ''}>Completed</option>
            <option value="Cancelled" ${a.status === 'Cancelled' ? 'selected' : ''}>Cancelled</option>
          </select>
        </td>
      `;
      apptTableBody.appendChild(tr);
    });
  }

  apptTableBody.addEventListener('change', async (e) => {
    const select = e.target.closest('.status-update');
    if (!select) return;
    const id = Number(select.dataset.id);
    const appt = myAppointments.find(a => a.id === id);
    const previousStatus = appt.status;
    const patientName = (appt.patient && appt.patient.name) || 'Patient';

    try {
      // Real PUT — this is what makes the status change visible to the
      // patient too (they get a notification from the same call).
      await LifetagAPI.updateAppointment(id, { status: select.value });
      appt.status = select.value;
      showToast(`${patientName}'s appointment marked as ${select.value}.`);
      logActivity('Updated', 'Appointment', `${patientName} → ${select.value}`);
    } catch (err) {
      select.value = previousStatus;
      showToast(`Update failed: ${err.message}`);
    }
  });

  searchInput.addEventListener('input', renderMyAppointments);
  statusFilter.addEventListener('change', renderMyAppointments);
  renderMyAppointments();
}
