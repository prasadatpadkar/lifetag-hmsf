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

// ---------- Add a new bed / room ----------
// Works on both the Admin and the Reception page: the button and the pop-up are created here,
// so no page markup is needed. Uses POST /api/rooms-beds (Admin + Reception are allowed).
(function setupAddBed() {
  const header = document.querySelector('.content__header');
  if (!header) return;
  const WARDS = ['General Ward', 'ICU', 'Maternity', 'Pediatric'];

  const bar = document.createElement('div');
  bar.style.cssText = 'display:flex;justify-content:flex-end;margin:0 0 14px';
  bar.innerHTML = `<button type="button" class="btn-add" id="openAddBed">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 5v14M5 12h14"/></svg>
      Add Bed / Room</button>`;
  header.insertAdjacentElement('afterend', bar);

  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.id = 'addBedOverlay';
  modal.innerHTML = `
    <div class="modal">
      <div class="modal__head">
        <h2>Add Bed / Room</h2>
        <button class="modal__close" type="button" id="addBedClose" aria-label="Close">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6L6 18M6 6l12 12"/></svg>
        </button>
      </div>
      <form id="addBedForm" novalidate>
        <div class="form-grid">
          <div class="field field--full">
            <label for="addWard">Ward</label>
            <select id="addWard">${WARDS.map(w => `<option>${w}</option>`).join('')}</select>
          </div>
          <div class="field">
            <label for="addRoomNo">Room number</label>
            <input type="text" id="addRoomNo" placeholder="e.g. 104" maxlength="30">
          </div>
          <div class="field">
            <label for="addBedNo">Bed label</label>
            <input type="text" id="addBedNo" placeholder="e.g. GEN-004" maxlength="30">
          </div>
          <div class="field field--full">
            <label for="addCharge">Charge per day (&#8377;)</label>
            <input type="number" id="addCharge" min="0" step="50" placeholder="e.g. 1200">
          </div>
          <div class="field field--full"><span class="field-error" id="addBedError" style="display:block"></span></div>
        </div>
        <div class="modal__footer">
          <button type="button" class="btn-secondary" id="addBedCancel">Cancel</button>
          <button type="submit" class="btn-primary" style="width:auto; padding:10px 20px;">Add bed</button>
        </div>
      </form>
    </div>`;
  document.body.appendChild(modal);

  const f = {
    ward: modal.querySelector('#addWard'), room: modal.querySelector('#addRoomNo'),
    bed: modal.querySelector('#addBedNo'), charge: modal.querySelector('#addCharge'),
    err: modal.querySelector('#addBedError'), form: modal.querySelector('#addBedForm')
  };

  // Suggest the rate other beds in that ward already use.
  function suggestCharge() {
    const sample = beds.find(b => b.roomType === f.ward.value && b.chargePerDay != null);
    f.charge.value = sample ? sample.chargePerDay : '';
  }
  f.ward.addEventListener('change', suggestCharge);

  function open() {
    f.form.reset(); f.err.textContent = '';
    f.ward.value = WARDS.includes(activeWard) ? activeWard : WARDS[0];
    suggestCharge();
    modal.classList.add('is-open');
    f.room.focus();
  }
  function close() { modal.classList.remove('is-open'); }
  bar.querySelector('#openAddBed').addEventListener('click', open);
  modal.querySelector('#addBedClose').addEventListener('click', close);
  modal.querySelector('#addBedCancel').addEventListener('click', close);
  modal.addEventListener('click', (e) => { if (e.target === modal) close(); });

  f.form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const room = f.room.value.trim();
    const bedNo = f.bed.value.trim() || room;
    const charge = f.charge.value === '' ? null : Number(f.charge.value);
    f.err.textContent = '';
    if (!room) { f.err.textContent = 'Room number is required.'; return; }
    if (charge !== null && (isNaN(charge) || charge < 0)) { f.err.textContent = 'Charge per day cannot be negative.'; return; }
    const clash = beds.some(b => (b.bedNumber || b.roomNumber || '').toLowerCase() === bedNo.toLowerCase());
    if (clash) { f.err.textContent = `A bed labelled "${bedNo}" already exists. Use a different label.`; return; }

    const btn = f.form.querySelector('button[type="submit"]');
    btn.disabled = true;
    try {
      await LifetagAPI.createRoomBed({
        roomNumber: room, bedNumber: bedNo, roomType: f.ward.value,
        chargePerDay: charge, status: 'Available'
      });
      close();
      showToast(`Bed ${bedNo} added to ${f.ward.value}.`);
      logActivity('Created', 'Bed', `${bedNo} — ${f.ward.value}`);
      activeWard = f.ward.value;
      document.querySelectorAll('.ward-tab').forEach(t => t.classList.toggle('is-active', t.dataset.ward === activeWard));
      await loadAll();
    } catch (err) {
      f.err.textContent = `Could not add the bed: ${err.message}`;
    } finally {
      btn.disabled = false;
    }
  });
})();

// ---------- Initial load ----------
loadAll();
