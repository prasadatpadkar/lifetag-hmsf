// ============================================
// PATIENT MANAGEMENT — live data via LifetagAPI (see js/api-config.js)
// ============================================

let patients = [];
let editingId = null;

const tableBody = document.getElementById('patientTableBody');
const emptyState = document.getElementById('emptyState');
const searchInput = document.getElementById('patientSearch');
const bloodFilter = document.getElementById('bloodFilter');

const overlay = document.getElementById('patientModalOverlay');
const modalTitle = document.getElementById('modalTitle');
const form = document.getElementById('patientForm');
const toast = document.getElementById('toast');
const toastMessage = document.getElementById('toastMessage');

// ---------- Helpers ----------
function initials(name) {
  return (name || '?').split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
}

function showToast(message) {
  toastMessage.textContent = message;
  toast.classList.add('is-visible');
  setTimeout(() => toast.classList.remove('is-visible'), 2600);
}

// ---------- Load ----------
async function loadPatients() {
  try {
    patients = await LifetagAPI.getPatients();
    renderPatients();
  } catch (err) {
    tableBody.innerHTML = '';
    emptyState.style.display = 'block';
    emptyState.textContent = `Couldn't load patients: ${err.message}`;
  }
}

// ---------- Render ----------
function renderPatients() {
  const query = searchInput.value.trim().toLowerCase();
  const bloodValue = bloodFilter.value;

  const filtered = patients.filter(p => {
    const matchesQuery = p.name.toLowerCase().includes(query) || String(p.id).includes(query);
    const matchesBlood = !bloodValue || p.bloodGroup === bloodValue;
    return matchesQuery && matchesBlood;
  });

  tableBody.innerHTML = '';

  if (filtered.length === 0) {
    emptyState.textContent = 'No patients found. Try a different search or add a new patient.';
    emptyState.style.display = 'block';
    return;
  }
  emptyState.style.display = 'none';

  // Admin and Reception can open a patient's full medical record (Reception: read-only).
  const staffRole = (localStorage.getItem('lifetag_role') || '').toUpperCase();
  const canOpenRecord = staffRole === 'ADMIN' || staffRole === 'RECEPTION';
  const recordPage = staffRole === 'RECEPTION' ? 'reception-patient-record.html' : 'admin-patient-record.html';

  filtered.forEach(p => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>
        <div class="cell-person">
          <div class="cell-person__avatar">${escapeHtml(initials(p.name))}</div>
          <div>
            <div class="cell-person__name">${canOpenRecord ? `<a href="${recordPage}?id=${escapeHtml(p.id)}" title="Open full medical record" style="color:inherit;text-decoration:underline;text-underline-offset:3px">${escapeHtml(p.name)}</a>` : escapeHtml(p.name)}</div>
            <div class="cell-person__meta">Patient ID: LT-${escapeHtml(String(p.id).padStart(4, '0'))}</div>
          </div>
        </div>
      </td>
      <td>${escapeHtml(p.age ?? '—')} yrs &middot; ${escapeHtml(p.gender || '—')}</td>
      <td>${escapeHtml(p.contact || '—')}</td>
      <td><span class="badge-blood">${escapeHtml(p.bloodGroup || '—')}</span></td>
      <td>${escapeHtml(p.status || 'Active')}</td>
      <td>
        <div class="row-actions">
          ${canOpenRecord ? `
          <a class="edit-btn" href="${recordPage}?id=${escapeHtml(p.id)}" title="Medical record" aria-label="Medical record" style="display:inline-flex;align-items:center;justify-content:center">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 3h9l4 4v14H6z"/><path d="M14 3v5h5"/><path d="M9 13h6M9 17h6"/></svg>
          </a>` : ''}
          ${p.user ? `
          <button class="reset-pw-btn" data-id="${escapeHtml(p.id)}" aria-label="Reset password" title="Reset password">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12a9 9 0 1 0 3-6.7"/><path d="M3 4v5h5"/></svg>
          </button>` : ''}
          <button class="edit-btn" data-id="${escapeHtml(p.id)}" aria-label="Edit">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>
          </button>
          <button class="delete-btn" data-id="${escapeHtml(p.id)}" aria-label="Delete">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>
          </button>
        </div>
      </td>
    `;
    tableBody.appendChild(tr);
  });
}

// ---------- Modal open/close ----------
function openModal(mode, patient) {
  form.reset();
  if (mode === 'edit' && patient) {
    editingId = patient.id;
    modalTitle.textContent = 'Edit Patient';
    document.getElementById('fullName').value = patient.name;
    document.getElementById('age').value = patient.age ?? '';
    document.getElementById('gender').value = patient.gender || '';
    document.getElementById('contact').value = patient.contact || '';
    if (document.getElementById('email')) document.getElementById('email').value = patient.email || '';
    document.getElementById('bloodGroup').value = patient.bloodGroup || '';
    document.getElementById('address').value = patient.address || '';
    document.getElementById('status').value = patient.status || 'Active';
    document.getElementById('emergencyContact').value = patient.emergencyContact || '';
  } else {
    editingId = null;
    modalTitle.textContent = 'Add Patient';
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
  const emailField = document.getElementById('email');
  const data = {
    name: document.getElementById('fullName').value.trim(),
    age: Number(document.getElementById('age').value),
    gender: document.getElementById('gender').value,
    contact: document.getElementById('contact').value.trim(),
    email: emailField ? emailField.value.trim() : '',
    bloodGroup: document.getElementById('bloodGroup').value,
    address: document.getElementById('address').value.trim(),
    status: document.getElementById('status').value,
    emergencyContact: document.getElementById('emergencyContact').value.trim()
  };

  submitBtn.disabled = true;
  try {
    if (editingId) {
      await LifetagAPI.updatePatient(editingId, data);
      showToast('Patient details updated.');
      logActivity('Updated', 'Patient', data.name);
      closeModal();
    } else {
      const created = await LifetagAPI.createPatient(data);
      showToast('New patient added.');
      logActivity('Created', 'Patient', data.name);
      closeModal();
      if (created && created.tempUsername && created.tempPassword) {
        showCredentialsModal({ role: 'patient', username: created.tempUsername, password: created.tempPassword });
      }
    }
    await loadPatients();
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
    const patient = patients.find(p => p.id === id);
    const confirmed = confirm(`Issue a new temporary password for ${patient.name}? Their current password will stop working immediately.`);
    if (confirmed) {
      try {
        const updated = await LifetagAPI.resetPatientPassword(id);
        logActivity('Reset password', 'Patient', patient.name);
        if (updated && updated.tempUsername && updated.tempPassword) {
          showCredentialsModal({ role: 'patient', username: updated.tempUsername, password: updated.tempPassword });
        }
      } catch (err) {
        showToast(`Reset failed: ${err.message}`);
      }
    }
    return;
  }

  if (editBtn) {
    const id = Number(editBtn.dataset.id);
    const patient = patients.find(p => p.id === id);
    openModal('edit', patient);
  }

  if (deleteBtn) {
    const id = Number(deleteBtn.dataset.id);
    const patient = patients.find(p => p.id === id);
    const confirmed = confirm(`Remove ${patient.name} from patient records?`);
    if (confirmed) {
      try {
        await LifetagAPI.deletePatient(id);
        showToast('Patient removed.');
        logActivity('Deleted', 'Patient', patient.name);
        await loadPatients();
      } catch (err) {
        showToast(`Delete failed: ${err.message}`);
      }
    }
  }
});

// ---------- Search & filter ----------
searchInput.addEventListener('input', renderPatients);
bloodFilter.addEventListener('change', renderPatients);

// ---------- Initial load ----------
loadPatients();
