// ============================================
// ADMISSIONS — register of inpatient stays (reception-admissions.html and
// admission-management.html). Admit = POST /api/clinical/admissions (bed becomes
// Occupied); Discharge frees the bed. Charges are billed from the invoice screen
// via "Import charges" (room days x rate are calculated there).
// ============================================
const role = (localStorage.getItem('lifetag_role') || '').toUpperCase();
const E = (v) => escapeHtml(v == null || v === '' ? '—' : String(v));
const fdate = (d) => d ? new Date(String(d).slice(0, 10) + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }) : '—';
const inr = (n) => '₹' + (Number(n) || 0).toLocaleString('en-IN');
const tbody = document.getElementById('admBody');
const empty = document.getElementById('emptyState');
const statusSel = document.getElementById('statusFilter');
const search = document.getElementById('admSearch');
const overlay = document.getElementById('admOverlay');
let admissions = [], patients = [], doctors = [], beds = [];

function toast(msg) {
  const t = document.getElementById('toast'), m = document.getElementById('toastMessage');
  m.textContent = msg; t.classList.add('is-visible'); setTimeout(() => t.classList.remove('is-visible'), 2600);
}

async function load() {
  try {
    [admissions, patients, doctors, beds] = await Promise.all([
      LifetagAPI.getAdmissions(), LifetagAPI.getPatients(), LifetagAPI.getDoctors(), LifetagAPI.getRoomsBeds()]);
    render();
  } catch (err) { empty.style.display = 'block'; empty.textContent = err.message; }
}

function render() {
  const q = search.value.trim().toLowerCase(), st = statusSel.value;
  const rows = admissions.filter(a => (!st || a.status === st) &&
    (!q || ((a.patient && a.patient.name) || '').toLowerCase().includes(q) || (a.diagnosis || '').toLowerCase().includes(q)));
  document.getElementById('statCurrent').textContent = admissions.filter(a => a.status === 'Admitted').length;
  document.getElementById('statTotal').textContent = admissions.length;
  document.getElementById('statFree').textContent = beds.filter(b => b.status === 'Available').length;
  empty.style.display = rows.length ? 'none' : 'block';
  tbody.innerHTML = rows.slice(0, 300).map(a => `<tr>
    <td><div class="cell-person__name">${E(a.patient && a.patient.name)}</div><div class="cell-person__meta">${E(a.diagnosis || a.reason)}</div></td>
    <td>${E(a.doctor && a.doctor.name)}</td>
    <td>${E(a.wardType)}<div class="cell-person__meta">${E(a.roomNumber)}${a.bedNumber ? ' · ' + E(a.bedNumber) : ''} · ${inr(a.chargePerDay)}/day</div></td>
    <td>${fdate(a.admittedOn)}</td><td>${a.dischargedOn ? fdate(a.dischargedOn) : '—'}</td>
    <td>${a.daysStayed || 1}</td>
    <td><span class="status-pill ${a.status === 'Admitted' ? 'pending' : 'completed'}">${E(a.status)}</span></td>
    <td>${a.status === 'Admitted' ? `<button class="btn-secondary" data-discharge="${a.id}" style="padding:5px 12px">Discharge</button>` : ''}</td></tr>`).join('');
}

[search, statusSel].forEach(el => el.addEventListener('input', render));

document.getElementById('openAdmit').addEventListener('click', () => {
  document.getElementById('aPatient').innerHTML = '<option value="">Select patient</option>' + patients.map(p => `<option value="${p.id}">${E(p.name)} (LT-${String(p.id).padStart(4, '0')})</option>`).join('');
  document.getElementById('aDoctor').innerHTML = '<option value="">Select doctor</option>' + doctors.map(d => `<option value="${d.id}">${E(d.name)} — ${E(d.specialization)}</option>`).join('');
  document.getElementById('aBed').innerHTML = '<option value="">Select bed</option>' + beds.filter(b => b.status === 'Available')
    .map(b => `<option value="${b.id}">${E(b.roomType)} · ${E(b.roomNumber)} (${inr(b.chargePerDay)}/day)</option>`).join('');
  document.getElementById('aDate').value = new Date().toISOString().slice(0, 10);
  overlay.classList.add('is-open');
});
const close = () => overlay.classList.remove('is-open');
document.getElementById('admClose').addEventListener('click', close);
document.getElementById('admCancel').addEventListener('click', close);

document.getElementById('admForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const v = (id) => document.getElementById(id).value;
  if (!v('aPatient') || !v('aBed')) { toast('Choose a patient and a bed.'); return; }
  try {
    await LifetagAPI.admitPatient({ patientId: Number(v('aPatient')), doctorId: v('aDoctor') ? Number(v('aDoctor')) : null,
      roomBedId: Number(v('aBed')), reason: v('aReason'), diagnosis: v('aDx'), admittedOn: v('aDate') });
    close(); e.target.reset(); toast('Patient admitted.'); await load();
  } catch (err) { toast(err.message); }
});

tbody.addEventListener('click', async (e) => {
  const b = e.target.closest('[data-discharge]'); if (!b) return;
  const summary = prompt('Discharge summary (optional):', '');
  if (summary === null) return;
  try {
    await LifetagAPI.dischargePatient(b.dataset.discharge, { dischargeSummary: summary });
    toast('Patient discharged. Import the charges from Billing to generate the invoice.'); await load();
  } catch (err) { toast(err.message); }
});
load();
