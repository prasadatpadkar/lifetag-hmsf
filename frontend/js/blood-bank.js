// ============================================
// BLOOD BANK MANAGEMENT — live data via LifetagAPI (see js/api-config.js)
// Stock cards are an aggregate (sum of units per group) of the same
// /api/blood-bank rows shown in the Donors table below them.
// ============================================

let bloodEntries = [];  // one row per donation (donor + units + group)
let requests = [];
let allPatients = [];
let editingDonorId = null;
let editingRequestId = null;
let activeSection = 'donors';

const stockGrid = document.getElementById('stockGrid');
const donorTableBody = document.getElementById('donorTableBody');
const requestTableBody = document.getElementById('requestTableBody');
const donorEmptyState = document.getElementById('donorEmptyState');
const requestEmptyState = document.getElementById('requestEmptyState');
const donorsPanel = document.getElementById('donorsPanel');
const requestsPanel = document.getElementById('requestsPanel');

const searchInput = document.getElementById('bbSearch');
const bloodGroupFilter = document.getElementById('bloodGroupFilter');
const addBtnLabel = document.getElementById('addBtnLabel');
const openAddModalBtn = document.getElementById('openAddModal');

const toast = document.getElementById('toast');
const toastMessage = document.getElementById('toastMessage');
const requestPatientSelect = document.getElementById('requestPatient');

// ---------- Helpers ----------
function showToast(message) {
  toastMessage.textContent = message;
  toast.classList.add('is-visible');
  setTimeout(() => toast.classList.remove('is-visible'), 2600);
}

function stockLevel(units) {
  if (units <= 5) return 'critical';
  if (units <= 12) return 'low';
  return 'healthy';
}

function stockLabel(units) {
  if (units <= 5) return 'Critical';
  if (units <= 12) return 'Low';
  return 'Healthy';
}

// ---------- Load ----------
async function loadAll() {
  try {
    [bloodEntries, requests, allPatients] = await Promise.all([
      LifetagAPI.getBloodBank(),
      LifetagAPI.getBloodRequests(),
      LifetagAPI.getPatients()
    ]);
    requestPatientSelect.innerHTML = '<option value="">Select patient</option>';
    allPatients.forEach(p => {
      const opt = document.createElement('option');
      opt.value = p.id;
      opt.textContent = p.name;
      requestPatientSelect.appendChild(opt);
    });
    renderStock();
    renderDonors();
    renderRequests();
  } catch (err) {
    stockGrid.innerHTML = `<div class="empty-state">Couldn't load blood bank data: ${escapeHtml(err.message)}</div>`;
  }
}

// ---------- Render stock cards (aggregated by group) ----------
function renderStock() {
  const groups = ['A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-'];
  const totals = Object.fromEntries(groups.map(g => [g, 0]));
  const today = new Date().toISOString().slice(0, 10);
  bloodEntries.forEach(e => {
    const expired = e.expiryDate && e.expiryDate < today;       // expired blood cannot be issued
    if (totals[e.bloodGroup] !== undefined && !expired) totals[e.bloodGroup] += e.unitsAvailable || 0;
  });

  stockGrid.innerHTML = groups.map(g => `
    <div class="blood-card ${stockLevel(totals[g])}">
      <div class="blood-card__group">${escapeHtml(g)}</div>
      <div class="blood-card__units">${escapeHtml(totals[g])} units</div>
      <div class="blood-card__tag">${stockLabel(totals[g])}</div>
    </div>
  `).join('');
}

// ---------- Render donors ----------
function renderDonors() {
  const query = searchInput.value.trim().toLowerCase();
  const groupValue = bloodGroupFilter.value;

  const filtered = bloodEntries.filter(d => {
    const matchesQuery = (d.donorName || '').toLowerCase().includes(query);
    const matchesGroup = !groupValue || d.bloodGroup === groupValue;
    return matchesQuery && matchesGroup;
  });

  donorTableBody.innerHTML = '';
  donorEmptyState.style.display = filtered.length === 0 ? 'block' : 'none';

  filtered.forEach(d => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><div class="cell-person__name">${escapeHtml(d.donorName || 'Unknown donor')}</div></td>
      <td><span class="badge-blood">${escapeHtml(d.bloodGroup)}</span></td>
      <td>${d.donorContact ? escapeHtml(d.donorContact) : '&mdash;'}</td>
      <td>${d.lastDonationDate ? escapeHtml(new Date(d.lastDonationDate + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })) : '&mdash;'}</td>
      <td>${escapeHtml(d.unitsAvailable ?? 0)}</td>
      <td>${d.expiryDate ? escapeHtml(new Date(d.expiryDate + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })) + (d.expiryDate < new Date().toISOString().slice(0, 10) ? ' <span class="status-pill cancelled">Expired</span>' : '') : '&mdash;'}</td>
      <td>₹${escapeHtml(Number(d.pricePerUnit ?? 1500).toLocaleString('en-IN'))}</td>
      <td>
        <div class="row-actions">
          <button class="edit-btn donor-edit" data-id="${escapeHtml(d.id)}" aria-label="Edit">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>
          </button>
          <button class="delete-btn donor-delete" data-id="${escapeHtml(d.id)}" aria-label="Delete">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>
          </button>
        </div>
      </td>
    `;
    donorTableBody.appendChild(tr);
  });
}

// ---------- Render requests ----------
function renderRequests() {
  const query = searchInput.value.trim().toLowerCase();
  const groupValue = bloodGroupFilter.value;

  const filtered = requests.filter(r => {
    const patientName = (r.patient && r.patient.name) || '';
    const matchesQuery = patientName.toLowerCase().includes(query);
    const matchesGroup = !groupValue || r.bloodGroup === groupValue;
    return matchesQuery && matchesGroup;
  });

  requestTableBody.innerHTML = '';
  requestEmptyState.style.display = filtered.length === 0 ? 'block' : 'none';

  filtered.forEach(r => {
    const patientName = (r.patient && r.patient.name) || 'Unknown patient';
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><div class="cell-person__name">${escapeHtml(patientName)}</div></td>
      <td><span class="badge-blood">${escapeHtml(r.bloodGroup)}</span></td>
      <td>${escapeHtml(r.units)}</td>
      <td>${r.ward ? escapeHtml(r.ward) : '&mdash;'}</td>
      <td><span class="status-pill ${escapeHtml((r.status || '').toLowerCase())}">${escapeHtml(r.status)}</span></td>
      <td>${r.status === 'Fulfilled'
        ? `₹${escapeHtml(Number(r.totalCharge || 0).toLocaleString('en-IN'))}<div class="cell-person__meta">${r.billed ? 'On invoice' : 'To be invoiced'}${r.admission ? ' · inpatient bill' : ''}</div>`
        : '&mdash;'}</td>
      <td>
        <div class="row-actions">
          ${r.status === 'Fulfilled' || r.status === 'Cancelled' ? '<span class="cell-person__meta">Locked</span>' : `
          <button class="edit-btn request-edit" data-id="${escapeHtml(r.id)}" aria-label="Edit / fulfil">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>
          </button>
          <button class="delete-btn request-delete" data-id="${escapeHtml(r.id)}" aria-label="Delete">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>
          </button>`}
        </div>
      </td>
    `;
    requestTableBody.appendChild(tr);
  });
}

// ---------- Section tab switching ----------
document.querySelectorAll('.section-tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.section-tab').forEach(t => t.classList.remove('is-active'));
    tab.classList.add('is-active');
    activeSection = tab.dataset.section;

    if (activeSection === 'donors') {
      donorsPanel.style.display = 'block';
      requestsPanel.style.display = 'none';
      addBtnLabel.textContent = 'Add Donor';
    } else {
      donorsPanel.style.display = 'none';
      requestsPanel.style.display = 'block';
      addBtnLabel.textContent = 'New Request';
    }
  });
});

// ---------- Donor modal ----------
const donorOverlay = document.getElementById('donorModalOverlay');
const donorForm = document.getElementById('donorForm');

function openDonorModal(mode, donor) {
  donorForm.reset();
  if (mode === 'edit' && donor) {
    editingDonorId = donor.id;
    document.getElementById('donorModalTitle').textContent = 'Edit Donor';
    document.getElementById('donorName').value = donor.donorName || '';
    document.getElementById('donorBloodGroup').value = donor.bloodGroup;
    document.getElementById('donorContact').value = donor.donorContact || '';
    document.getElementById('donorLastDonated').value = donor.lastDonationDate || '';
    document.getElementById('donorUnits').value = donor.unitsAvailable ?? 0;
    document.getElementById('donorExpiry').value = donor.expiryDate || '';
    document.getElementById('donorPrice').value = donor.pricePerUnit ?? 1500;
  } else {
    editingDonorId = null;
    document.getElementById('donorModalTitle').textContent = 'Add Donor';
  }
  donorOverlay.classList.add('is-open');
}
function closeDonorModal() { donorOverlay.classList.remove('is-open'); }

document.getElementById('closeDonorModal').addEventListener('click', closeDonorModal);
document.getElementById('cancelDonorModal').addEventListener('click', closeDonorModal);
donorOverlay.addEventListener('click', (e) => { if (e.target === donorOverlay) closeDonorModal(); });

donorForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const submitBtn = donorForm.querySelector('button[type="submit"]');
  const existing = editingDonorId ? bloodEntries.find(d => d.id === editingDonorId) : null;
  const data = {
    bloodGroup: document.getElementById('donorBloodGroup').value,
    donorName: document.getElementById('donorName').value.trim(),
    donorContact: document.getElementById('donorContact').value.trim(),
    lastDonationDate: document.getElementById('donorLastDonated').value || null,
    unitsAvailable: Number(document.getElementById('donorUnits').value) || 0,
    expiryDate: document.getElementById('donorExpiry').value || null,
    pricePerUnit: Number(document.getElementById('donorPrice').value) || 0
  };

  submitBtn.disabled = true;
  try {
    if (editingDonorId) {
      await LifetagAPI.updateBloodEntry(editingDonorId, data);
      showToast('Donor details updated.');
      logActivity('Updated', 'Blood Bank', data.donorName);
    } else {
      await LifetagAPI.createBloodEntry(data);
      showToast('New donor added.');
      logActivity('Created', 'Blood Bank', data.donorName);
    }
    closeDonorModal();
    await loadAll();
  } catch (err) {
    showToast(`Save failed: ${err.message}`);
  } finally {
    submitBtn.disabled = false;
  }
});

// ---------- Request modal ----------
const requestOverlay = document.getElementById('requestModalOverlay');
const requestForm = document.getElementById('requestForm');

function openRequestModal(mode, request) {
  requestForm.reset();
  if (mode === 'edit' && request) {
    editingRequestId = request.id;
    document.getElementById('requestModalTitle').textContent = 'Edit Blood Request';
    requestPatientSelect.value = request.patient ? request.patient.id : '';
    document.getElementById('requestBloodGroup').value = request.bloodGroup;
    document.getElementById('requestUnits').value = request.units;
    document.getElementById('requestWard').value = request.ward || '';
    document.getElementById('requestStatus').value = request.status;
  } else {
    editingRequestId = null;
    document.getElementById('requestModalTitle').textContent = 'New Blood Request';
    document.getElementById('requestStatus').value = 'Requested';
  }
  requestOverlay.classList.add('is-open');
}
function closeRequestModal() { requestOverlay.classList.remove('is-open'); }

document.getElementById('closeRequestModal').addEventListener('click', closeRequestModal);
document.getElementById('cancelRequestModal').addEventListener('click', closeRequestModal);
requestOverlay.addEventListener('click', (e) => { if (e.target === requestOverlay) closeRequestModal(); });

requestForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const submitBtn = requestForm.querySelector('button[type="submit"]');
  const patientId = Number(requestPatientSelect.value);
  const patientName = (allPatients.find(p => p.id === patientId) || {}).name || 'patient';
  const data = {
    patientId,
    bloodGroup: document.getElementById('requestBloodGroup').value,
    units: Number(document.getElementById('requestUnits').value),
    ward: document.getElementById('requestWard').value.trim(),
    status: document.getElementById('requestStatus').value
  };

  submitBtn.disabled = true;
  try {
    if (editingRequestId) {
      await LifetagAPI.updateBloodRequest(editingRequestId, data);
      showToast(data.status === 'Fulfilled'
        ? 'Blood issued from stock - the charge was added to the patient\'s bill.'
        : 'Request updated.');
      logActivity('Updated', 'Blood Request', patientName);
    } else {
      await LifetagAPI.createBloodRequest(data);
      showToast('New blood request logged.');
      logActivity('Created', 'Blood Request', patientName);
    }
    closeRequestModal();
    await loadAll();
  } catch (err) {
    showToast(`Save failed: ${err.message}`);
  } finally {
    submitBtn.disabled = false;
  }
});

// ---------- Add button routes to correct modal ----------
openAddModalBtn.addEventListener('click', () => {
  if (activeSection === 'donors') openDonorModal('add');
  else openRequestModal('add');
});

// ---------- Edit / Delete (event delegation) ----------
donorTableBody.addEventListener('click', async (e) => {
  const editBtn = e.target.closest('.donor-edit');
  const deleteBtn = e.target.closest('.donor-delete');
  if (editBtn) {
    const donor = bloodEntries.find(d => d.id === Number(editBtn.dataset.id));
    openDonorModal('edit', donor);
  }
  if (deleteBtn) {
    const donor = bloodEntries.find(d => d.id === Number(deleteBtn.dataset.id));
    if (confirm(`Remove ${donor.donorName || 'this donor'} from donor records?`)) {
      try {
        await LifetagAPI.deleteBloodEntry(donor.id);
        showToast('Donor removed.');
        logActivity('Deleted', 'Blood Bank', donor.donorName);
        await loadAll();
      } catch (err) {
        showToast(`Delete failed: ${err.message}`);
      }
    }
  }
});

requestTableBody.addEventListener('click', async (e) => {
  const editBtn = e.target.closest('.request-edit');
  const deleteBtn = e.target.closest('.request-delete');
  if (editBtn) {
    const request = requests.find(r => r.id === Number(editBtn.dataset.id));
    openRequestModal('edit', request);
  }
  if (deleteBtn) {
    const request = requests.find(r => r.id === Number(deleteBtn.dataset.id));
    const patientName = (request.patient && request.patient.name) || 'this patient';
    if (confirm(`Remove this blood request for ${patientName}?`)) {
      try {
        await LifetagAPI.deleteBloodRequest(request.id);
        showToast('Request removed.');
        logActivity('Deleted', 'Blood Request', patientName);
        await loadAll();
      } catch (err) {
        showToast(`Delete failed: ${err.message}`);
      }
    }
  }
});

// ---------- Search & filter ----------
searchInput.addEventListener('input', () => { renderDonors(); renderRequests(); });
bloodGroupFilter.addEventListener('change', () => { renderDonors(); renderRequests(); });

// ---------- Initial load ----------
loadAll();
