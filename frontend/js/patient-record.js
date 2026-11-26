// ============================================
// PATIENT MEDICAL RECORD — one script for doctor-patient-record.html,
// admin-patient-record.html and patient-records.html.
// Data: GET /api/clinical/patient/{id}/summary (vitals, reports, treatments,
// admissions, appointments). Doctors see the patient's WHOLE history, but only
// for patients they have an appointment with (enforced server-side).
// Doctor/Admin can add vitals, reports and treatments; patients are read-only.
// ============================================

const role = (localStorage.getItem('lifetag_role') || '').toUpperCase();
const canEdit = role === 'DOCTOR' || role === 'ADMIN';
const root = document.getElementById('recordRoot');
const qs = new URLSearchParams(window.location.search);
let rec = null;
let patientId = null;
let charts = [];

const fdate = (d) => d ? new Date(String(d).slice(0, 10) + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }) : '—';
const fdt = (d) => d ? new Date(d).toLocaleString('en-IN', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';
const inr = (n) => '₹' + (Number(n) || 0).toLocaleString('en-IN');
const E = (v) => escapeHtml(v == null || v === '' ? '—' : String(v));
const parse = (s) => { try { const a = JSON.parse(s || '[]'); return Array.isArray(a) ? a : []; } catch (e) { return []; } };
const toast = (msg) => {
  const t = document.getElementById('toast'), m = document.getElementById('toastMessage');
  if (!t || !m) { alert(msg); return; }
  m.textContent = msg; t.classList.add('is-visible'); setTimeout(() => t.classList.remove('is-visible'), 2600);
};

function typeClass(t) {
  const x = (t || '').toUpperCase();
  if (x.includes('MRI')) return 'MRI';
  if (x.includes('CT')) return 'CT';
  if (x.includes('X-RAY') || x.includes('XRAY')) return 'XRAY';
  if (['ECG', 'ECHO', 'TMT', 'HOLTER'].some(k => x.includes(k))) return 'CARDIAC';
  if (x.includes('BLOOD') || x.includes('URINE') || x.includes('LAB') || x.includes('PROFILE') || x.includes('HBA1C')) return 'LAB';
  return '';
}

function bpClass(s, d) {
  if (s == null || d == null) return '';
  if (s >= 180 || d >= 120) return 'bad';
  if (s >= 140 || d >= 90) return 'bad';
  if (s >= 130 || d >= 80) return 'warn';
  return 'good';
}

(async function init() {
  try {
    if (role === 'PATIENT') {
      const me = await LifetagAPI.getMyPatientProfile();
      patientId = me.id;
    } else {
      patientId = Number(qs.get('id'));
    }
    if (!patientId) { root.innerHTML = '<div class="empty-state">No patient selected. Open a record from the patient list.</div>'; return; }
    rec = await LifetagAPI.getPatientRecord(patientId);
    render();
  } catch (err) {
    root.innerHTML = `<div class="rec-alert"><strong>Unable to open this record.</strong> ${escapeHtml(err.message)}</div>`;
  }
})();

function render() {
  charts.forEach(c => c.destroy()); charts = [];
  const p = rec.patient;
  const vitals = rec.vitals || [], reports = rec.reports || [], treatments = rec.treatments || [];
  const admissions = rec.admissions || [], appts = rec.appointments || [];
  const ongoing = treatments.filter(t => t.status === 'Ongoing');
  const current = admissions.find(a => a.status === 'Admitted');
  const latest = vitals[0];
  const initials = (p.name || '?').split(' ').filter(Boolean).map(w => w[0]).join('').slice(0, 2).toUpperCase();
  const activeTab = (root.querySelector('.rec-tab.is-active') || {}).dataset?.tab || 'overview';

  root.innerHTML = `
    <div class="rec-head">
      <div class="rec-head__avatar">${E(initials)}</div>
      <div class="rec-head__main">
        <h2 class="rec-head__name">${E(p.name)}</h2>
        <div class="rec-head__meta">
          <span>ID LT-${String(p.id).padStart(4, '0')}</span><span>${E(p.age)} yrs · ${E(p.gender)}</span>
          <span>Blood group ${E(p.bloodGroup)}</span><span>${E(p.contact)}</span>
        </div>
      </div>
      <div class="rec-chips">
        ${current ? `<span class="chip chip--warn">Admitted · ${E(current.roomNumber || current.roomBed)}</span>` : ''}
        ${ongoing.length ? `<span class="chip chip--amber">${ongoing.length} ongoing treatment${ongoing.length > 1 ? 's' : ''}</span>` : '<span class="chip chip--ok">No active treatment</span>'}
      </div>
    </div>
    ${p.allergies ? `<div class="rec-alert"><strong>Allergies:</strong> ${E(p.allergies)}</div>` : ''}
    ${p.chronicConditions ? `<div class="rec-alert" style="background:rgba(216,168,0,0.08);border-color:rgba(216,168,0,0.4)"><strong style="color:#A67C00">Chronic conditions:</strong> ${E(p.chronicConditions)}</div>` : ''}

    <div class="rec-tabs">
      ${[['overview', 'Overview'], ['vitals', 'Vitals & BP', vitals.length], ['reports', 'Reports', reports.length],
         ['treatments', 'Treatments', treatments.length], ['admissions', 'Admissions', admissions.length], ['visits', 'Visits', appts.length]]
        .map(([k, l, c]) => `<button class="rec-tab ${k === activeTab ? 'is-active' : ''}" data-tab="${k}">${l}${c != null ? `<span class="count">${c}</span>` : ''}</button>`).join('')}
    </div>

    <div class="rec-pane" data-pane="overview">${overviewHtml(latest, ongoing, reports, treatments, admissions, appts)}</div>
    <div class="rec-pane" data-pane="vitals">${vitalsHtml(vitals)}</div>
    <div class="rec-pane" data-pane="reports">${reportsHtml(reports)}</div>
    <div class="rec-pane" data-pane="treatments">${treatmentsHtml(treatments)}</div>
    <div class="rec-pane" data-pane="admissions">${admissionsHtml(admissions)}</div>
    <div class="rec-pane" data-pane="visits">${visitsHtml(appts)}</div>
  `;
  showTab(activeTab);
  drawCharts(vitals);
}

function showTab(name) {
  root.querySelectorAll('.rec-tab').forEach(b => b.classList.toggle('is-active', b.dataset.tab === name));
  root.querySelectorAll('.rec-pane').forEach(x => x.classList.toggle('is-active', x.dataset.pane === name));
  charts.forEach(c => c.resize());
}

// ---------------------------------------------------------------- overview
function overviewHtml(latest, ongoing, reports, treatments, admissions, appts) {
  const stat = (label, val, sub, cls) => `<div class="rec-stat ${cls || ''}"><div class="rec-stat__label">${label}</div><div class="rec-stat__value">${val}</div><div class="rec-stat__sub">${sub || ''}</div></div>`;
  const bp = latest && latest.systolic != null ? `${latest.systolic}/${latest.diastolic}` : '—';
  const bpc = latest ? bpClass(latest.systolic, latest.diastolic) : '';
  const stats = `<div class="rec-grid">
    ${stat('Blood pressure', bp, latest ? fdate(latest.recordedAt) : 'no readings', bpc === 'bad' ? 'rec-stat--bad' : bpc === 'good' ? 'rec-stat--good' : '')}
    ${stat('Pulse', latest && latest.pulse != null ? latest.pulse + ' bpm' : '—')}
    ${stat('SpO₂', latest && latest.spo2 != null ? latest.spo2 + '%' : '—', '', latest && latest.spo2 != null && latest.spo2 < 94 ? 'rec-stat--bad' : '')}
    ${stat('Blood sugar', latest && latest.bloodSugar != null ? latest.bloodSugar + ' mg/dL' : '—', latest ? E(latest.sugarType) : '')}
    ${stat('Weight', latest && latest.weightKg != null ? latest.weightKg + ' kg' : '—', latest && latest.heightCm ? 'BMI ' + (latest.weightKg / Math.pow(latest.heightCm / 100, 2)).toFixed(1) : '')}
    ${stat('Reports on file', reports.length, `${reports.filter(r => r.resultStatus && r.resultStatus !== 'Normal').length} abnormal`)}
  </div>`;

  const events = [];
  reports.forEach(r => events.push({ d: r.reportDate, cls: 'is-report', t: `${r.reportType} — ${r.title || ''}`, x: r.impression || r.findings || '' }));
  treatments.forEach(t => events.push({ d: t.startDate, cls: '', t: `Treatment: ${t.diagnosis}`, x: [t.treatmentType, t.procedureName].filter(Boolean).join(' · ') }));
  admissions.forEach(a => events.push({ d: a.admittedOn, cls: 'is-admission', t: `Admitted: ${a.diagnosis || a.reason || ''}`, x: `${a.wardType || ''} ${a.roomNumber || ''}${a.dischargedOn ? ' · discharged ' + fdate(a.dischargedOn) : ' · currently admitted'}` }));
  events.sort((a, b) => String(b.d).localeCompare(String(a.d)));
  const tl = events.slice(0, 12).map(e => `<div class="timeline__item ${e.cls}"><div class="timeline__date">${fdate(e.d)}</div><div class="timeline__title">${E(e.t)}</div><div class="timeline__text">${escapeHtml((e.x || '').slice(0, 180))}</div></div>`).join('');

  const meds = ongoing.flatMap(t => parse(t.medicationsJson).map(m => ({ ...m, dx: t.diagnosis })));
  const medHtml = meds.length ? `<table class="med-table"><thead><tr><th>Medicine</th><th>Dose</th><th>Frequency</th><th>For</th></tr></thead><tbody>${meds.map(m => `<tr><td>${E(m.name)}</td><td>${E(m.dose)}</td><td>${E(m.frequency)}</td><td>${E(m.dx)}</td></tr>`).join('')}</tbody></table>` : '<div class="rec-item__sub">No current medication on file.</div>';

  return stats + `<div class="rec-two"><div class="rec-card"><h3>Recent history</h3>${tl ? `<div class="timeline">${tl}</div>` : '<div class="rec-item__sub">Nothing recorded yet.</div>'}</div>
    <div><div class="rec-card"><h3>Current medication</h3>${medHtml}</div>
    <div class="rec-card"><h3>BP trend</h3><div class="rec-chart" style="height:200px"><canvas id="bpMini"></canvas></div></div></div></div>`;
}

// ---------------------------------------------------------------- vitals
function vitalsHtml(vitals) {
  const rows = vitals.map(v => {
    const c = bpClass(v.systolic, v.diastolic);
    return `<tr><td>${fdt(v.recordedAt)}</td>
      <td><strong style="color:${c === 'bad' ? 'var(--coral-500)' : c === 'warn' ? '#A67C00' : 'inherit'}">${v.systolic != null ? v.systolic + '/' + v.diastolic : '—'}</strong></td>
      <td>${E(v.pulse)}</td><td>${E(v.temperatureC)}</td><td>${E(v.spo2)}</td><td>${v.bloodSugar != null ? v.bloodSugar + ' (' + E(v.sugarType) + ')' : '—'}</td>
      <td>${E(v.weightKg)}</td><td>${E(v.recordedBy)}</td>
      ${canEdit ? `<td><button class="edit-btn" data-del-vitals="${v.id}" aria-label="Delete"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/></svg></button></td>` : ''}</tr>`;
  }).join('');
  return `<div class="rec-toolbar"><div class="grow"></div>${canEdit ? '<button class="btn-add" data-add="vitals">+ Record vitals</button>' : ''}</div>
    <div class="rec-card"><h3>Blood pressure &amp; pulse over time</h3><div class="rec-chart"><canvas id="bpChart"></canvas></div></div>
    <div class="rec-card"><h3>Blood sugar &amp; weight</h3><div class="rec-chart"><canvas id="sugarChart"></canvas></div></div>
    <div class="panel panel--full" style="overflow-x:auto"><table class="data-table data-table--wide"><thead><tr><th>When</th><th>BP</th><th>Pulse</th><th>Temp °C</th><th>SpO₂</th><th>Sugar mg/dL</th><th>Wt kg</th><th>By</th>${canEdit ? '<th></th>' : ''}</tr></thead>
    <tbody>${rows || '<tr><td colspan="9">No vitals recorded.</td></tr>'}</tbody></table></div>`;
}

function drawCharts(vitals) {
  if (typeof Chart === 'undefined') return;
  const asc = vitals.slice().reverse();
  const labels = asc.map(v => fdate(v.recordedAt));
  const grid = { color: 'rgba(128,128,128,0.15)' };
  const line = (label, key, color) => ({ label, data: asc.map(v => v[key]), borderColor: color, backgroundColor: color, tension: 0.25, pointRadius: 3, spanGaps: true });
  const mk = (id, datasets, extra) => {
    const el = document.getElementById(id); if (!el) return;
    charts.push(new Chart(el, { type: 'line', data: { labels, datasets }, options: Object.assign({ responsive: true, maintainAspectRatio: false, scales: { x: { grid, ticks: { maxTicksLimit: 8 } }, y: { grid } }, plugins: { legend: { position: 'bottom' } } }, extra || {}) }));
  };
  mk('bpChart', [line('Systolic', 'systolic', '#C1352A'), line('Diastolic', 'diastolic', '#2236C4'), line('Pulse', 'pulse', '#8895A0')]);
  mk('bpMini', [line('Systolic', 'systolic', '#C1352A'), line('Diastolic', 'diastolic', '#2236C4')]);
  const el = document.getElementById('sugarChart');
  if (el) charts.push(new Chart(el, { type: 'line', data: { labels, datasets: [line('Blood sugar', 'bloodSugar', '#A67C00'), Object.assign(line('Weight (kg)', 'weightKg', '#16374D'), { yAxisID: 'y2' })] },
    options: { responsive: true, maintainAspectRatio: false, scales: { x: { grid, ticks: { maxTicksLimit: 8 } }, y: { grid }, y2: { position: 'right', grid: { display: false } } }, plugins: { legend: { position: 'bottom' } } } }));
}

// ---------------------------------------------------------------- reports
function reportsHtml(reports) {
  const cats = [...new Set(reports.map(r => r.category).filter(Boolean))];
  const items = reports.map(r => {
    const res = parse(r.resultsJson);
    const table = res.length ? `<h4>Results</h4><table class="lab-table"><thead><tr><th>Test</th><th>Value</th><th>Unit</th><th>Reference</th><th>Flag</th></tr></thead><tbody>${res.map(x => `<tr><td>${E(x.test)}</td><td><strong>${E(x.value)}</strong></td><td>${E(x.unit)}</td><td>${E(x.range)}</td><td><span class="flag-${escapeHtml(x.flag || 'N')}">${E(x.flag || 'N')}</span></td></tr>`).join('')}</tbody></table>` : '';
    const st = (r.resultStatus || 'Normal');
    return `<div class="rec-item" data-cat="${escapeHtml(r.category || '')}" data-q="${escapeHtml(((r.reportType || '') + ' ' + (r.title || '') + ' ' + (r.findings || '')).toLowerCase())}">
      <div class="rec-item__head" data-toggle>
        <span class="type-pill ${typeClass(r.reportType)}">${E(r.reportType)}</span>
        <span class="rec-item__title">${E(r.title)}</span>
        <span class="rec-item__sub">${fdate(r.reportDate)}${r.doctor ? ' · ' + E(r.doctor.name) : ''}</span>
        <span class="res-pill ${st.toLowerCase()}">${E(st)}</span>
      </div>
      <div class="rec-item__body">
        ${r.findings ? `<h4>Findings</h4><p>${E(r.findings)}</p>` : ''}
        ${r.impression ? `<h4>Impression</h4><p>${E(r.impression)}</p>` : ''}
        ${table}
        <h4>Details</h4><p>${E(r.labName)} · performed by ${E(r.performedBy)} · ${r.cost ? 'charge ' + inr(r.cost) : 'no charge recorded'} · ${r.billed ? 'billed' : 'not yet billed'}</p>
        ${r.reportUrl ? `<p><a href="${escapeHtml(r.reportUrl)}" target="_blank" rel="noopener">Open scanned report</a></p>` : ''}
        ${canEdit ? `<p style="margin-top:8px"><button class="btn-secondary" data-del-report="${r.id}">Delete report</button></p>` : ''}
      </div></div>`;
  }).join('');
  return `<div class="rec-toolbar">
      <select id="repCat" class="select-filter"><option value="">All categories</option>${cats.map(c => `<option>${E(c)}</option>`).join('')}</select>
      <input id="repQ" class="select-filter" placeholder="Search MRI, CT, lipid…" style="min-width:200px">
      <div class="grow"></div>${canEdit ? '<button class="btn-add" data-add="report">+ Add report</button>' : ''}</div>
    <div id="repList">${items || '<div class="empty-state">No reports on file.</div>'}</div>`;
}

// ---------------------------------------------------------------- treatments
function treatmentsHtml(list) {
  const items = list.map(t => {
    const meds = parse(t.medicationsJson);
    const medT = meds.length ? `<h4>Medication</h4><table class="med-table"><thead><tr><th>Medicine</th><th>Dose</th><th>Frequency</th><th>Duration</th></tr></thead><tbody>${meds.map(m => `<tr><td>${E(m.name)}</td><td>${E(m.dose)}</td><td>${E(m.frequency)}</td><td>${E(m.duration)}</td></tr>`).join('')}</tbody></table>` : '';
    const st = t.status || 'Ongoing';
    const cls = st === 'Ongoing' ? 'abnormal' : st === 'Completed' ? 'normal' : 'critical';
    return `<div class="rec-item"><div class="rec-item__head" data-toggle>
        <span class="type-pill">${E(t.treatmentType)}</span><span class="rec-item__title">${E(t.diagnosis)}${t.diagnosisCode ? ' (' + E(t.diagnosisCode) + ')' : ''}</span>
        <span class="rec-item__sub">${fdate(t.startDate)}${t.endDate ? ' → ' + fdate(t.endDate) : ''}${t.doctor ? ' · ' + E(t.doctor.name) : ''}</span>
        <span class="res-pill ${cls}">${E(st)}</span></div>
      <div class="rec-item__body">
        ${t.procedureName ? `<h4>Procedure</h4><p>${E(t.procedureName)}</p>` : ''}
        ${t.plan ? `<h4>Plan</h4><p>${E(t.plan)}</p>` : ''}
        ${medT}
        ${t.outcome ? `<h4>Outcome</h4><p>${E(t.outcome)}</p>` : ''}
        ${t.followUpDate ? `<h4>Follow-up</h4><p>${fdate(t.followUpDate)}</p>` : ''}
        ${t.notes ? `<h4>Notes</h4><p>${E(t.notes)}</p>` : ''}
        <h4>Cost</h4><p>Procedure ${inr(t.procedureCost)} · Medication ${inr(t.medicationCost)}</p>
        ${canEdit ? `<p style="margin-top:8px">${st === 'Ongoing' ? `<button class="btn-secondary" data-complete="${t.id}">Mark completed</button> ` : ''}<button class="btn-secondary" data-del-treat="${t.id}">Delete</button></p>` : ''}
      </div></div>`;
  }).join('');
  return `<div class="rec-toolbar"><div class="grow"></div>${canEdit ? '<button class="btn-add" data-add="treatment">+ Add treatment</button>' : ''}</div>${items || '<div class="empty-state">No treatments on file.</div>'}`;
}

// ---------------------------------------------------------------- admissions / visits
function admissionsHtml(list) {
  const bar = canEdit ? '<div class="rec-toolbar"><div class="grow"></div><button class="btn-add" data-add="blood">+ Request blood</button></div>' : '';
  if (!list.length) return bar + '<div class="empty-state">No admissions.</div>';
  return bar + list.map(a => `<div class="rec-item"><div class="rec-item__head" data-toggle>
      <span class="type-pill ${a.status === 'Admitted' ? 'CARDIAC' : ''}">${E(a.wardType)}</span>
      <span class="rec-item__title">${E(a.diagnosis || a.reason)}</span>
      <span class="rec-item__sub">${fdate(a.admittedOn)} → ${a.dischargedOn ? fdate(a.dischargedOn) : 'present'} · ${a.daysStayed || 1} day(s)</span>
      <span class="res-pill ${a.status === 'Admitted' ? 'critical' : 'normal'}">${E(a.status)}</span></div>
    <div class="rec-item__body"><h4>Room</h4><p>${E(a.roomNumber)} ${a.bedNumber ? '· bed ' + E(a.bedNumber) : ''} · ${inr(a.chargePerDay)}/day</p>
      ${a.reason ? `<h4>Reason</h4><p>${E(a.reason)}</p>` : ''}${a.dischargeSummary ? `<h4>Discharge summary</h4><p>${E(a.dischargeSummary)}</p>` : ''}
      ${a.doctor ? `<h4>Doctor</h4><p>${E(a.doctor.name)}</p>` : ''}</div></div>`).join('');
}

function visitsHtml(list) {
  if (!list.length) return '<div class="empty-state">No visits.</div>';
  return `<div class="panel panel--full" style="overflow-x:auto"><table class="data-table data-table--wide"><thead><tr><th>Date</th><th>Doctor</th><th>Reason</th><th>Status</th></tr></thead><tbody>${list.map(a => `<tr><td>${fdate(a.appointmentDate)} ${E((a.appointmentTime || '').toString().slice(0, 5))}</td><td>${E(a.doctor && a.doctor.name)}</td><td>${E(a.reason)}</td><td><span class="status-pill ${escapeHtml((a.status || '').toLowerCase())}">${E(a.status)}</span></td></tr>`).join('')}</tbody></table></div>`;
}

// ---------------------------------------------------------------- events
root.addEventListener('click', async (e) => {
  const tab = e.target.closest('.rec-tab'); if (tab) { showTab(tab.dataset.tab); return; }
  const tg = e.target.closest('[data-toggle]'); if (tg) { tg.parentElement.classList.toggle('is-open'); return; }
  const add = e.target.closest('[data-add]'); if (add) { openForm(add.dataset.add); return; }
  try {
    const dv = e.target.closest('[data-del-vitals]');
    if (dv && confirm('Delete this vitals entry?')) { await LifetagAPI.deleteVitals(dv.dataset.delVitals); await reload('vitals'); return; }
    const dr = e.target.closest('[data-del-report]');
    if (dr && confirm('Delete this report?')) { await LifetagAPI.deleteReport(dr.dataset.delReport); await reload('reports'); return; }
    const dt = e.target.closest('[data-del-treat]');
    if (dt && confirm('Delete this treatment?')) { await LifetagAPI.deleteTreatment(dt.dataset.delTreat); await reload('treatments'); return; }
    const cm = e.target.closest('[data-complete]');
    if (cm) {
      await LifetagAPI.updateTreatment(cm.dataset.complete, { status: 'Completed', endDate: new Date().toISOString().slice(0, 10) });
      await reload('treatments'); return;
    }
  } catch (err) { toast(err.message); }
});
root.addEventListener('input', (e) => { if (e.target.id === 'repCat' || e.target.id === 'repQ') filterReports(); });
function filterReports() {
  const c = document.getElementById('repCat').value, q = document.getElementById('repQ').value.trim().toLowerCase();
  root.querySelectorAll('#repList .rec-item').forEach(el => {
    el.style.display = (!c || el.dataset.cat === c) && (!q || el.dataset.q.includes(q)) ? '' : 'none';
  });
}
async function reload(tab) {
  rec = await LifetagAPI.getPatientRecord(patientId);
  root.dataset.keep = tab;
  render(); showTab(tab);
}

// ---------------------------------------------------------------- add forms
const overlay = document.getElementById('formOverlay');
const formEl = document.getElementById('recForm');
let formKind = null, doctors = [];

async function openForm(kind) {
  formKind = kind;
  const f = (id, label, type, extra) => `<div class="field ${extra && extra.full ? 'field--full' : ''}"><label for="${id}">${label}</label>${type === 'textarea' ? `<textarea id="${id}" rows="3"></textarea>` : `<input id="${id}" type="${type || 'text'}" ${extra && extra.attrs || ''}>`}</div>`;
  const sel = (id, label, opts) => `<div class="field"><label for="${id}">${label}</label><select id="${id}">${opts.map(o => `<option>${o}</option>`).join('')}</select></div>`;
  let doctorSel = '';
  if (role === 'ADMIN') {
    if (!doctors.length) { try { doctors = await LifetagAPI.getDoctors(); } catch (e) { doctors = []; } }
    doctorSel = `<div class="field"><label for="f_doctor">Doctor</label><select id="f_doctor">${doctors.map(d => `<option value="${d.id}">${E(d.name)}</option>`).join('')}</select></div>`;
  }
  const today = new Date().toISOString().slice(0, 10);
  let title = '', html = '';
  if (kind === 'vitals') {
    title = 'Record vitals';
    html = f('f_sys', 'Systolic (mmHg)', 'number') + f('f_dia', 'Diastolic (mmHg)', 'number') + f('f_pulse', 'Pulse (bpm)', 'number') + f('f_temp', 'Temperature (°C)', 'number', { attrs: 'step="0.1"' }) +
      f('f_spo2', 'SpO₂ (%)', 'number') + f('f_sugar', 'Blood sugar (mg/dL)', 'number') + sel('f_sugarType', 'Sugar type', ['Random', 'Fasting', 'Post-meal']) + f('f_wt', 'Weight (kg)', 'number', { attrs: 'step="0.1"' }) +
      f('f_ht', 'Height (cm)', 'number') + f('f_notes', 'Notes', 'textarea', { full: true });
  } else if (kind === 'blood') {
    title = 'Request blood from the blood bank';
    html = sel('f_bgroup', 'Blood group', ['A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-']) + f('f_units', 'Units needed', 'number', { attrs: 'min="1" max="20" value="1"' }) +
      f('f_ward', 'Ward / location', 'text') + sel('f_urgency', 'Priority', ['Requested', 'Urgent']) +
      '<div class="field field--full" style="font-size:12.5px;color:var(--text-2)">The blood bank issues the units; the charge is then added to this patient\'s bill automatically.</div>';
  } else if (kind === 'report') {
    title = 'Add diagnostic report';
    html = doctorSel + sel('f_cat', 'Category', ['Radiology', 'Laboratory', 'Cardiac', 'Special Tests']) +
      sel('f_type', 'Report type', ['MRI', 'CT Scan', 'X-Ray', 'Ultrasound', 'ECG', '2D Echo', 'TMT', 'Blood Test', 'Urine Test', 'Other']) +
      f('f_title', 'Title', 'text', { full: true }) + f('f_date', 'Report date', 'date') + sel('f_res', 'Result', ['Normal', 'Abnormal', 'Critical']) + f('f_lab', 'Lab / centre', 'text') + f('f_cost', 'Charge (₹)', 'number') +
      f('f_find', 'Findings', 'textarea', { full: true }) + f('f_imp', 'Impression', 'textarea', { full: true });
  } else {
    title = 'Add treatment';
    html = doctorSel + f('f_dx', 'Diagnosis', 'text', { full: true }) + f('f_code', 'ICD code', 'text') +
      sel('f_ttype', 'Type', ['Medication', 'Surgery', 'Procedure', 'Therapy', 'Lifestyle', 'Observation']) + f('f_proc', 'Procedure name', 'text') + f('f_start', 'Start date', 'date') +
      f('f_follow', 'Follow-up date', 'date') + f('f_pcost', 'Procedure cost (₹)', 'number') + f('f_mcost', 'Medication cost (₹)', 'number') +
      f('f_plan', 'Plan', 'textarea', { full: true }) + f('f_meds', 'Medicines (one per line: name | dose | frequency | duration)', 'textarea', { full: true });
  }
  document.getElementById('formTitle').textContent = title;
  formEl.innerHTML = `<div class="form-grid">${html}</div>`;
  if (kind === 'blood') { const g = document.getElementById('f_bgroup'); if (g && rec.patient.bloodGroup) g.value = rec.patient.bloodGroup; }
  ['f_date', 'f_start'].forEach(id => { const el = document.getElementById(id); if (el) el.value = today; });
  overlay.classList.add('is-open');
}

const closeForm = () => overlay.classList.remove('is-open');
document.getElementById('closeForm').addEventListener('click', closeForm);
document.getElementById('cancelForm').addEventListener('click', closeForm);
overlay.addEventListener('click', (e) => { if (e.target === overlay) closeForm(); });

const val = (id) => { const el = document.getElementById(id); return el && el.value !== '' ? el.value : null; };
const num = (id) => { const v = val(id); return v == null ? null : Number(v); };

document.getElementById('saveForm').addEventListener('click', async () => {
  const doctorId = val('f_doctor');
  try {
    if (formKind === 'vitals') {
      const body = { patientId, systolic: num('f_sys'), diastolic: num('f_dia'), pulse: num('f_pulse'), temperatureC: num('f_temp'), spo2: num('f_spo2'),
        bloodSugar: num('f_sugar'), sugarType: val('f_sugarType'), weightKg: num('f_wt'), heightCm: num('f_ht'), notes: val('f_notes') };
      if (Object.entries(body).every(([k, v]) => k === 'patientId' || k === 'sugarType' || v == null)) throw new Error('Enter at least one reading.');
      await LifetagAPI.addVitals(body); closeForm(); await reload('vitals');
    } else if (formKind === 'blood') {
      await LifetagAPI.createBloodRequest({ patientId, bloodGroup: val('f_bgroup'), units: num('f_units') || 1, ward: val('f_ward') || '', status: val('f_urgency') });
      closeForm();
    } else if (formKind === 'report') {
      if (!val('f_title')) throw new Error('Give the report a title.');
      await LifetagAPI.addReport({ patientId, doctorId, category: val('f_cat'), reportType: val('f_type'), title: val('f_title'), reportDate: val('f_date'),
        resultStatus: val('f_res'), labName: val('f_lab'), cost: num('f_cost') || 0, findings: val('f_find'), impression: val('f_imp'), status: 'Completed' });
      closeForm(); await reload('reports');
    } else {
      if (!val('f_dx')) throw new Error('Diagnosis is required.');
      const meds = (val('f_meds') || '').split('\n').map(l => l.trim()).filter(Boolean).map(l => { const [name, dose, frequency, duration] = l.split('|').map(s => (s || '').trim()); return { name, dose, frequency, duration }; });
      await LifetagAPI.addTreatment({ patientId, doctorId, diagnosis: val('f_dx'), diagnosisCode: val('f_code'), treatmentType: val('f_ttype'), procedureName: val('f_proc'),
        startDate: val('f_start'), followUpDate: val('f_follow'), procedureCost: num('f_pcost') || 0, medicationCost: num('f_mcost') || 0, plan: val('f_plan'),
        medicationsJson: JSON.stringify(meds), status: 'Ongoing' });
      closeForm(); await reload('treatments');
    }
    toast('Saved.');
  } catch (err) { toast(err.message); }
});
