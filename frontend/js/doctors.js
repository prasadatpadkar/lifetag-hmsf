// ============================================
// DOCTOR MANAGEMENT — live data via LifetagAPI (see js/api-config.js)
// ============================================

let doctors = [];
let editingId = null;

const tableBody = document.getElementById('doctorTableBody');
const emptyState = document.getElementById('emptyState');
const searchInput = document.getElementById('doctorSearch');
const specializationFilter = document.getElementById('specializationFilter');
const statusFilter = document.getElementById('statusFilter');

const overlay = document.getElementById('doctorModalOverlay');
const modalTitle = document.getElementById('modalTitle');
const form = document.getElementById('doctorForm');
const toast = document.getElementById('toast');
const toastMessage = document.getElementById('toastMessage');

// ---------- Helpers ----------
function initials(name) {
  return (name || '?').replace('Dr. ', '').split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
}

function statusClass(status) {
  if (status === 'On Duty' || status === 'Active') return 'onduty';
  if (status === 'Off Duty') return 'offduty';
  return 'onleave';
}

function showToast(message) {
  toastMessage.textContent = message;
  toast.classList.add('is-visible');
  setTimeout(() => toast.classList.remove('is-visible'), 2600);
}

// ---------- Load ----------
async function loadDoctors() {
  try {
    doctors = await LifetagAPI.getDoctors();
    renderDoctors();
  } catch (err) {
    tableBody.innerHTML = '';
    emptyState.style.display = 'block';
    emptyState.textContent = `Couldn't load doctors: ${err.message}`;
  }
}

// ---------- Render ----------
function renderDoctors() {
  const query = searchInput.value.trim().toLowerCase();
  const specValue = specializationFilter.value;
  const statusValue = statusFilter.value;

  const filtered = doctors.filter(d => {
    const matchesQuery = d.name.toLowerCase().includes(query) || String(d.id).includes(query);
    const matchesSpec = !specValue || d.specialization === specValue;
    const matchesStatus = !statusValue || d.status === statusValue;
    return matchesQuery && matchesSpec && matchesStatus;
  });

  tableBody.innerHTML = '';

  if (filtered.length === 0) {
    emptyState.style.display = 'block';
    return;
  }
  emptyState.style.display = 'none';

  filtered.forEach(d => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>
        <div class="cell-person">
          <div class="cell-person__avatar">${escapeHtml(initials(d.name))}</div>
          <div>
            <div class="cell-person__name">${escapeHtml(d.name)}</div>
            <div class="cell-person__meta">Doctor ID: DR-${escapeHtml(String(d.id).padStart(4, '0'))}</div>
          </div>
        </div>
      </td>
      <td>${escapeHtml(d.specialization || '—')}</td>
      <td>${escapeHtml(d.contact || '—')}</td>
      <td>${escapeHtml(d.experienceYears ?? '—')} yrs</td>
      <td><span class="status-pill ${statusClass(d.status)}">${escapeHtml(d.status || 'Active')}</span></td>
      <td>
        <div class="row-actions">
          <button class="reset-pw-btn" data-id="${escapeHtml(d.id)}" aria-label="Reset password" title="Reset password">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12a9 9 0 1 0 3-6.7"/><path d="M3 4v5h5"/></svg>
          </button>
          <button class="edit-btn" data-id="${escapeHtml(d.id)}" aria-label="Edit">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>
          </button>
          <button class="delete-btn" data-id="${escapeHtml(d.id)}" aria-label="Delete">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>
          </button>
        </div>
      </td>
    `;
    tableBody.appendChild(tr);
  });
}

// ---------- Modal open/close ----------
function openModal(mode, doctor) {
  form.reset();
  if (mode === 'edit' && doctor) {
    editingId = doctor.id;
    modalTitle.textContent = 'Edit Doctor';
    document.getElementById('fullName').value = doctor.name;
    document.getElementById('specialization').value = doctor.specialization || '';
    document.getElementById('experience').value = doctor.experienceYears ?? '';
    document.getElementById('contact').value = doctor.contact || '';
    document.getElementById('email').value = doctor.email || '';
    document.getElementById('consultationFee').value = doctor.consultationFee ?? '';
    document.getElementById('status').value = doctor.status || 'Active';
    document.getElementById('availableDays').value = doctor.availableDays || '';
  } else {
    editingId = null;
    modalTitle.textContent = 'Add Doctor';
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
  const data = {
    name: document.getElementById('fullName').value.trim(),
    specialization: document.getElementById('specialization').value,
    experienceYears: Number(document.getElementById('experience').value),
    contact: document.getElementById('contact').value.trim(),
    email: document.getElementById('email').value.trim(),
    status: document.getElementById('status').value,
    availableDays: document.getElementById('availableDays').value.trim(),
    consultationFee: document.getElementById('consultationFee').value === '' ? null : Number(document.getElementById('consultationFee').value)
  };

  submitBtn.disabled = true;
  try {
    if (editingId) {
      await LifetagAPI.updateDoctor(editingId, data);
      showToast('Doctor details updated.');
      logActivity('Updated', 'Doctor', data.name);
      closeModal();
    } else {
      const created = await LifetagAPI.createDoctor(data);
      showToast('New doctor added.');
      logActivity('Created', 'Doctor', data.name);
      closeModal();
      if (created && created.tempUsername && created.tempPassword) {
        showCredentialsModal({ role: 'doctor', username: created.tempUsername, password: created.tempPassword });
      }
    }
    await loadDoctors();
  } catch (err) {
    showToast(`Save failed: ${err.message}`);
  } finally {
    submitBtn.disabled = false;
  }
});

// ---------- Edit / Reset password / Delete (event delegation) ----------
tableBody.addEventListener('click', async (e) => {
  const editBtn = e.target.closest('.edit-btn');
  const deleteBtn = e.target.closest('.delete-btn');
  const resetBtn = e.target.closest('.reset-pw-btn');

  if (resetBtn) {
    const id = Number(resetBtn.dataset.id);
    const doctor = doctors.find(d => d.id === id);
    const confirmed = confirm(`Issue a new temporary password for ${doctor.name}? Their current password will stop working immediately.`);
    if (confirmed) {
      try {
        const updated = await LifetagAPI.resetDoctorPassword(id);
        logActivity('Reset password', 'Doctor', doctor.name);
        if (updated && updated.tempUsername && updated.tempPassword) {
          showCredentialsModal({ role: 'doctor', username: updated.tempUsername, password: updated.tempPassword });
        }
      } catch (err) {
        showToast(`Reset failed: ${err.message}`);
      }
    }
    return;
  }

  if (editBtn) {
    const id = Number(editBtn.dataset.id);
    const doctor = doctors.find(d => d.id === id);
    openModal('edit', doctor);
  }

  if (deleteBtn) {
    const id = Number(deleteBtn.dataset.id);
    const doctor = doctors.find(d => d.id === id);
    const confirmed = confirm(`Remove ${doctor.name} from doctor records? Their login will also be deactivated.`);
    if (confirmed) {
      try {
        await LifetagAPI.deleteDoctor(id);
        showToast('Doctor removed.');
        logActivity('Deleted', 'Doctor', doctor.name);
        await loadDoctors();
      } catch (err) {
        showToast(`Delete failed: ${err.message}`);
      }
    }
  }
});

// ---------- Search & filters ----------
searchInput.addEventListener('input', renderDoctors);
specializationFilter.addEventListener('change', renderDoctors);
statusFilter.addEventListener('change', renderDoctors);

// ---------- Initial load ----------
loadDoctors();
