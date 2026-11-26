// ============================================
// ROOM & BED MANAGEMENT — live data via LifetagAPI (see js/api-config.js)
// `roomType` on the backend doubles as the "ward" grouping used here
// (General Ward / ICU / Maternity / Pediatric).
// ============================================

let beds = [];
let allPatients = [];
let activeWard = 'All';
let editingBedId = null;

const wardTabs = document.getElementById('wardTabs');
const wardSections = document.getElementById('wardSections');

const overlay = document.getElementById('bedModalOverlay');
const modalTitle = document.getElementById('modalTitle');
const form = document.getElementById('bedForm');
const toast = document.getElementById('toast');
const toastMessage = document.getElementById('toastMessage');
const bedStatus = document.getElementById('bedStatus');
const bedPatient = document.getElementById('bedPatient');
const patientFieldWrap = document.getElementById('patientFieldWrap');

// ---------- Helpers ----------
function showToast(message) {
  toastMessage.textContent = message;
  toast.classList.add('is-visible');
  setTimeout(() => toast.classList.remove('is-visible'), 2600);
}

function statusClass(status) {
  return (status || '').toLowerCase();
}

// ---------- Load ----------
async function loadAll() {
  try {
    [beds, allPatients] = await Promise.all([
      LifetagAPI.getRoomsBeds(),
      LifetagAPI.getPatients()
    ]);
    bedPatient.innerHTML = '<option value="">Select patient</option>';
    allPatients.forEach(p => {
      const opt = document.createElement('option');
      opt.value = p.id;
      opt.textContent = p.name;
      bedPatient.appendChild(opt);
    });
    renderWards();
  } catch (err) {
    wardSections.innerHTML = `<div class="empty-state">Couldn't load rooms/beds: ${escapeHtml(err.message)}</div>`;
  }
}

// ---------- Render ----------
function renderWards() {
  const wards = ['General Ward', 'ICU', 'Maternity', 'Pediatric'];
  wardSections.innerHTML = '';

  wards.forEach(ward => {
    if (activeWard !== 'All' && activeWard !== ward) return;

    const wardBeds = beds.filter(b => b.roomType === ward);
    if (wardBeds.length === 0) return;
    const occupiedCount = wardBeds.filter(b => b.status === 'Occupied').length;

    const section = document.createElement('div');
    section.className = 'ward-section';
    section.innerHTML = `
      <h2 class="ward-section__title">
        ${escapeHtml(ward)}
        <span class="ward-section__count">${escapeHtml(occupiedCount)} / ${escapeHtml(wardBeds.length)} occupied</span>
      </h2>
      <div class="bed-grid">
        ${wardBeds.map(b => `
          <div class="bed-card ${escapeHtml(statusClass(b.status))}" data-id="${escapeHtml(b.id)}">
            <div class="bed-card__label">${escapeHtml(b.bedNumber || b.roomNumber)}</div>
            <div class="bed-card__status">${escapeHtml(b.status)}</div>
            <div class="bed-card__patient">${b.patient && b.patient.name ? escapeHtml(b.patient.name) : '&mdash;'}</div>
          </div>
        `).join('')}
      </div>
    `;
    wardSections.appendChild(section);
  });

  if (!wardSections.children.length) {
    wardSections.innerHTML = '<div class="empty-state">No beds found for this ward.</div>';
  }
}

// ---------- Ward tab switching ----------
wardTabs.addEventListener('click', (e) => {
  const tab = e.target.closest('.ward-tab');
  if (!tab) return;
  document.querySelectorAll('.ward-tab').forEach(t => t.classList.remove('is-active'));
  tab.classList.add('is-active');
  activeWard = tab.dataset.ward;
  renderWards();
});

// ---------- Toggle patient field based on status ----------
function togglePatientField() {
  patientFieldWrap.style.display = bedStatus.value === 'Occupied' ? 'block' : 'none';
  if (bedStatus.value !== 'Occupied') bedPatient.value = '';
}
bedStatus.addEventListener('change', togglePatientField);

// ---------- Modal open/close ----------
function openModal(bed) {
  editingBedId = bed.id;
  modalTitle.textContent = `Bed ${bed.bedNumber || bed.roomNumber}`;
  bedStatus.value = bed.status;
  bedPatient.value = bed.patient ? bed.patient.id : '';
  togglePatientField();
  overlay.classList.add('is-open');
}

function closeModal() {
  overlay.classList.remove('is-open');
}

document.getElementById('closeModal').addEventListener('click', closeModal);
document.getElementById('cancelModal').addEventListener('click', closeModal);
overlay.addEventListener('click', (e) => { if (e.target === overlay) closeModal(); });

// ---------- Bed click (event delegation) ----------
wardSections.addEventListener('click', (e) => {
  const card = e.target.closest('.bed-card');
  if (!card) return;
  const bed = beds.find(b => String(b.id) === card.dataset.id);
  openModal(bed);
});

// ---------- Save ----------
form.addEventListener('submit', async (e) => {
  e.preventDefault();

  const submitBtn = form.querySelector('button[type="submit"]');
  const bed = beds.find(b => b.id === editingBedId);
  const status = bedStatus.value;
  const patientId = status === 'Occupied' ? Number(bedPatient.value) : null;

  submitBtn.disabled = true;
  try {
    if (status === 'Occupied' && patientId) {
      // Assign endpoint sets status to Occupied and links the patient
      await LifetagAPI.assignRoomBed(editingBedId, patientId);
    } else if (status === 'Available') {
      await LifetagAPI.assignRoomBed(editingBedId, null);
    } else {
      // Maintenance / Cleaning — a plain status update, no patient
      await LifetagAPI.updateRoomBed(editingBedId, { ...bed, status, patient: null });
    }

    const patientName = patientId ? (allPatients.find(p => p.id === patientId) || {}).name : '';
    closeModal();
    showToast(status === 'Occupied' && patientId
      ? `Bed ${bed.bedNumber || bed.roomNumber} allotted - patient admitted and the room charge is now on their bill.`
      : status === 'Available' && bed.status === 'Occupied'
        ? `Bed ${bed.bedNumber || bed.roomNumber} vacated - patient discharged and the final bill is ready.`
        : `Bed ${bed.bedNumber || bed.roomNumber} updated.`);
    logActivity('Updated', 'Bed', `${bed.bedNumber || bed.roomNumber} — ${status}${patientName ? ' (' + patientName + ')' : ''}`);
    await loadAll();
  } catch (err) {
    showToast(`Update failed: ${err.message}`);
  } finally {
    submitBtn.disabled = false;
  }
});

// ---------- Initial load ----------
loadAll();
