// ============================================
// APPOINTMENT MANAGEMENT — live data via LifetagAPI (see js/api-config.js)
// ============================================

let appointments = [];
let allPatients = [];
let allDoctors = [];
let editingId = null;

const tableBody = document.getElementById('apptTableBody');
const emptyState = document.getElementById('emptyState');
const searchInput = document.getElementById('apptSearch');
const statusFilter = document.getElementById('statusFilter');
const dateFilter = document.getElementById('dateFilter');

const overlay = document.getElementById('apptModalOverlay');
const modalTitle = document.getElementById('modalTitle');
const form = document.getElementById('apptForm');
const toast = document.getElementById('toast');
const toastMessage = document.getElementById('toastMessage');
const patientSelect = document.getElementById('patientSelect');
const doctorSelect = document.getElementById('doctorSelect');

// ---------- Populate dropdowns ----------
function populateDropdowns() {
  patientSelect.innerHTML = '<option value="">Select patient</option>';
  allPatients.forEach(p => {
    const opt = document.createElement('option');
    opt.value = p.id;
    opt.textContent = p.name;
    patientSelect.appendChild(opt);
  });
  doctorSelect.innerHTML = '<option value="">Select doctor</option>';
  allDoctors.forEach(d => {
    const opt = document.createElement('option');
    opt.value = d.id;
    opt.textContent = `${d.name} · ${d.specialization || 'General'}`;
    doctorSelect.appendChild(opt);
  });
}

// ---------- Helpers ----------
function statusClass(status) {
  return (status || '').toLowerCase();
}

function formatDate(dateStr) {
  if (!dateStr) return '—';
  const d = new Date(dateStr + 'T00:00:00');
  return d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
}

function showToast(message) {
  toastMessage.textContent = message;
  toast.classList.add('is-visible');
  setTimeout(() => toast.classList.remove('is-visible'), 2600);
}

// ---------- Load ----------
async function loadAll() {
  try {
    [appointments, allPatients, allDoctors] = await Promise.all([
      LifetagAPI.getAppointments(),
      LifetagAPI.getPatients(),
      LifetagAPI.getDoctors()
    ]);
    populateDropdowns();
    renderAppointments();
  } catch (err) {
    tableBody.innerHTML = '';
    emptyState.style.display = 'block';
    emptyState.textContent = `Couldn't load appointments: ${err.message}`;
  }
}

// ---------- Render ----------
function renderAppointments() {
  const query = searchInput.value.trim().toLowerCase();
  const statusValue = statusFilter.value;
  const dateValue = dateFilter.value;

  const filtered = appointments.filter(a => {
    const patientName = (a.patient && a.patient.name) || '';
    const doctorName = (a.doctor && a.doctor.name) || '';
    const matchesQuery = patientName.toLowerCase().includes(query) || doctorName.toLowerCase().includes(query);
    const matchesStatus = !statusValue || a.status === statusValue;
    const matchesDate = !dateValue || a.appointmentDate === dateValue;
    return matchesQuery && matchesStatus && matchesDate;
  });

  // Sort by date then time
  filtered.sort((a, b) => `${a.appointmentDate || ''}${a.appointmentTime || ''}`.localeCompare(`${b.appointmentDate || ''}${b.appointmentTime || ''}`));

  tableBody.innerHTML = '';

  if (filtered.length === 0) {
    emptyState.style.display = 'block';
    return;
  }
  emptyState.style.display = 'none';

  filtered.forEach(a => {
    const patientName = (a.patient && a.patient.name) || 'Unknown patient';
    const doctorName = (a.doctor && a.doctor.name) || 'Unknown doctor';
    const time = (a.appointmentTime || '').toString().slice(0, 5);
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><div class="cell-person__name">${escapeHtml(patientName)}</div></td>
      <td>${escapeHtml(doctorName)}</td>
      <td>${escapeHtml(formatDate(a.appointmentDate))} &middot; ${escapeHtml(time)}</td>
      <td>${a.reason ? escapeHtml(a.reason) : '&mdash;'}</td>
      <td><span class="status-pill ${escapeHtml(statusClass(a.status))}">${escapeHtml(a.status)}</span></td>
      <td>
        <div class="row-actions">
          <button class="edit-btn" data-id="${escapeHtml(a.id)}" aria-label="Edit">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>
          </button>
          <button class="delete-btn" data-id="${escapeHtml(a.id)}" aria-label="Delete">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>
          </button>
        </div>
      </td>
    `;
    tableBody.appendChild(tr);
  });
}

// ---------- Modal open/close ----------
function openModal(mode, appt) {
  form.reset();
  if (mode === 'edit' && appt) {
    editingId = appt.id;
    modalTitle.textContent = 'Edit Appointment';
    patientSelect.value = appt.patient ? appt.patient.id : '';
    doctorSelect.value = appt.doctor ? appt.doctor.id : '';
    document.getElementById('apptDate').value = appt.appointmentDate || '';
    document.getElementById('apptTime').value = (appt.appointmentTime || '').toString().slice(0, 5);
    document.getElementById('reason').value = appt.reason || '';
    document.getElementById('status').value = appt.status || 'Scheduled';
  } else {
    editingId = null;
    modalTitle.textContent = 'New Appointment';
    document.getElementById('status').value = 'Pending';
  }
  overlay.classList.add('is-open');
}

function closeModal() {
  overlay.classList.remove('is-open');
}

document.getElementById('openAddModal').addEventListener('click', () => openModal('add'));
document.getElementById('closeModal').addEventListener('click', closeModal);
document.getElementById('cancelModal').addEventListener('click', closeModal);
overlay.addEventListener('click', (e) => { if (e.target === overlay) closeModal(); });

// ---------- Save (add or edit) ----------
form.addEventListener('submit', async (e) => {
  e.preventDefault();

  const submitBtn = form.querySelector('button[type="submit"]');
  const patientId = Number(patientSelect.value);
  const doctorId = Number(doctorSelect.value);
  const patientName = (allPatients.find(p => p.id === patientId) || {}).name || 'patient';
  const doctorName = (allDoctors.find(d => d.id === doctorId) || {}).name || 'doctor';

  const data = {
    patientId,
    doctorId,
    appointmentDate: document.getElementById('apptDate').value,
    appointmentTime: document.getElementById('apptTime').value,
    reason: document.getElementById('reason').value.trim(),
    status: document.getElementById('status').value
  };

  submitBtn.disabled = true;
  try {
    if (editingId) {
      await LifetagAPI.updateAppointment(editingId, data);
      showToast('Appointment updated.');
      logActivity('Updated', 'Appointment', `${patientName} with ${doctorName}`);
    } else {
      await LifetagAPI.createAppointment(data);
      showToast('Appointment scheduled.');
      logActivity('Created', 'Appointment', `${patientName} with ${doctorName}`);
    }
    closeModal();
    await loadAll();
  } catch (err) {
    showToast(`Save failed: ${err.message}`);
  } finally {
    submitBtn.disabled = false;
  }
});

// ---------- Edit / Delete (event delegation) ----------
tableBody.addEventListener('click', async (e) => {
  const editBtn = e.target.closest('.edit-btn');
  const deleteBtn = e.target.closest('.delete-btn');

  if (editBtn) {
    const id = Number(editBtn.dataset.id);
    const appt = appointments.find(a => a.id === id);
    openModal('edit', appt);
  }

  if (deleteBtn) {
    const id = Number(deleteBtn.dataset.id);
    const appt = appointments.find(a => a.id === id);
    const patientName = (appt.patient && appt.patient.name) || 'this patient';
    const doctorName = (appt.doctor && appt.doctor.name) || 'this doctor';
    const confirmed = confirm(`Cancel and remove ${patientName}'s appointment with ${doctorName}?`);
    if (confirmed) {
      try {
        await LifetagAPI.deleteAppointment(id);
        showToast('Appointment removed.');
        logActivity('Deleted', 'Appointment', `${patientName} with ${doctorName}`);
        await loadAll();
      } catch (err) {
        showToast(`Delete failed: ${err.message}`);
      }
    }
  }
});

// ---------- Search & filters ----------
searchInput.addEventListener('input', renderAppointments);
statusFilter.addEventListener('change', renderAppointments);
dateFilter.addEventListener('change', renderAppointments);

// ---------- Initial load ----------
loadAll();
