// ============================================
// GOVERNMENT SCHEMES — RECEPTION VIEW, live data via LifetagAPI
// Reception captures Aadhaar + beneficiary card details and submits
// claims, at the same touchpoint where they already register patients
// and generate bills. Unlike Insurance, a claim can only be submitted
// for an enrollment Admin has already marked "Verified" — Reception
// cannot self-certify eligibility for a government subsidy. Final
// approve/reject/settle authority on claims stays with Admin, same
// pattern as refunds staying with Admin in Billing.
// ============================================

let allPatients = [];
let allSchemes = [];
let allEnrollments = [];
let allClaims = [];

function currency(n) { return '₹' + (Number(n) || 0).toLocaleString('en-IN'); }
function statusClass(status) { return (status || '').toLowerCase().replace(/\s+/g, ''); }
function maskAadhaar(number) {
  const digits = String(number || '').replace(/\D/g, '');
  if (digits.length < 4) return '••••-••••-••••';
  return `••••-••••-${digits.slice(-4)}`;
}

function showToast(msg) {
  const toast = document.getElementById('toast');
  const toastMessage = document.getElementById('toastMessage');
  toastMessage.textContent = msg;
  toast.classList.add('is-visible');
  setTimeout(() => toast.classList.remove('is-visible'), 2400);
}

function populatePatientSelect(selectEl) {
  selectEl.innerHTML = '';
  allPatients.forEach(p => {
    const opt = document.createElement('option');
    opt.value = p.id;
    opt.textContent = p.name;
    selectEl.appendChild(opt);
  });
}

function populateSchemeSelect(selectEl, includeInactive) {
  selectEl.innerHTML = '';
  allSchemes.filter(s => includeInactive || s.status === 'Active').forEach(s => {
    const opt = document.createElement('option');
    opt.value = s.id;
    opt.textContent = s.name;
    selectEl.appendChild(opt);
  });
}

// ---------- Section tabs ----------
const sectionTabs = document.querySelectorAll('.section-tab');
const enrollmentsPanel = document.getElementById('enrollmentsPanel');
const claimsPanel = document.getElementById('claimsPanel');
sectionTabs.forEach(tab => {
  tab.addEventListener('click', () => {
    sectionTabs.forEach(t => t.classList.remove('is-active'));
    tab.classList.add('is-active');
    const section = tab.dataset.section;
    enrollmentsPanel.style.display = section === 'enrollments' ? 'block' : 'none';
    claimsPanel.style.display = section === 'claims' ? 'block' : 'none';
  });
});

// ============================================
// PATIENT ENROLLMENT
// ============================================
const enrollTableBody = document.getElementById('enrollTableBody');
const enrollEmptyState = document.getElementById('enrollEmptyState');
const enrollSearch = document.getElementById('enrollSearch');
const enrollOverlay = document.getElementById('enrollModalOverlay');
const enrollForm = document.getElementById('enrollForm');
const enrollModalTitle = document.getElementById('enrollModalTitle');
let editingEnrollmentId = null;

function renderEnrollments() {
  const query = enrollSearch.value.trim().toLowerCase();
  const filtered = allEnrollments.filter(en => ((en.patient && en.patient.name) || '').toLowerCase().includes(query));

  enrollTableBody.innerHTML = '';
  enrollEmptyState.style.display = filtered.length === 0 ? 'block' : 'none';

  filtered.forEach(en => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><div class="cell-person__name">${(en.patient && en.patient.name) || 'Unknown patient'}</div></td>
      <td>${(en.scheme && en.scheme.name) || '—'}</td>
      <td>${maskAadhaar(en.aadhaarNumber)}</td>
      <td>${en.beneficiaryCardId || '—'}</td>
      <td><span class="status-pill ${statusClass(en.status)}">${en.status}</span></td>
      <td>
        <div class="row-actions">
          <button class="edit-btn" data-id="${en.id}" aria-label="Edit">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>
          </button>
        </div>
      </td>
    `;
    enrollTableBody.appendChild(tr);
  });
}

function openEnrollModal(mode, enrollment) {
  enrollForm.reset();
  populatePatientSelect(document.getElementById('enrollPatient'));
  populateSchemeSelect(document.getElementById('enrollScheme'), true);
  if (mode === 'edit' && enrollment) {
    editingEnrollmentId = enrollment.id;
    enrollModalTitle.textContent = 'Edit Patient Enrollment';
    document.getElementById('enrollPatient').value = enrollment.patient ? enrollment.patient.id : '';
    document.getElementById('enrollScheme').value = enrollment.scheme ? enrollment.scheme.id : '';
    document.getElementById('enrollAadhaar').value = enrollment.aadhaarNumber || '';
    document.getElementById('enrollCardId').value = enrollment.beneficiaryCardId || '';
  } else {
    editingEnrollmentId = null;
    enrollModalTitle.textContent = 'Add Patient Enrollment';
  }
  enrollOverlay.classList.add('is-open');
}
function closeEnrollModal() { enrollOverlay.classList.remove('is-open'); }

document.getElementById('openEnrollModal').addEventListener('click', () => openEnrollModal('add'));
document.getElementById('closeEnrollModal').addEventListener('click', closeEnrollModal);
document.getElementById('cancelEnrollModal').addEventListener('click', closeEnrollModal);
enrollOverlay.addEventListener('click', (e) => { if (e.target === enrollOverlay) closeEnrollModal(); });

enrollForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const submitBtn = enrollForm.querySelector('button[type="submit"]');
  const aadhaar = document.getElementById('enrollAadhaar').value.replace(/\D/g, '');
  if (aadhaar.length !== 12) {
    showToast('Aadhaar number must be exactly 12 digits.');
    return;
  }
  const patientId = Number(document.getElementById('enrollPatient').value);
  const patientName = (allPatients.find(p => p.id === patientId) || {}).name || 'Patient';
  const schemeId = Number(document.getElementById('enrollScheme').value);
  const cardId = document.getElementById('enrollCardId').value.trim();

  const data = { patientId, schemeId, aadhaarNumber: aadhaar, beneficiaryCardId: cardId };

  submitBtn.disabled = true;
  try {
    if (editingEnrollmentId) {
      await LifetagAPI.updatePatientScheme(editingEnrollmentId, data);
      logActivity('Updated', 'Scheme Enrollment', patientName);
      showToast('Enrollment updated — sent back for verification.');
    } else {
      await LifetagAPI.enrollScheme(data);
      logActivity('Created', 'Scheme Enrollment', patientName);
      showToast('Enrollment submitted for Admin verification.');
    }
    closeEnrollModal();
    allEnrollments = await LifetagAPI.getPatientSchemes();
    renderEnrollments();
  } catch (err) {
    showToast(`Save failed: ${err.message}`);
  } finally {
    submitBtn.disabled = false;
  }
});

enrollTableBody.addEventListener('click', (e) => {
  const editBtn = e.target.closest('.edit-btn');
  if (!editBtn) return;
  const en = allEnrollments.find(e2 => e2.id === Number(editBtn.dataset.id));
  openEnrollModal('edit', en);
});

enrollSearch.addEventListener('input', renderEnrollments);

// ============================================
// CLAIMS (submit + track — only for Verified enrollments)
// ============================================
const claimTableBody = document.getElementById('claimTableBody');
const claimEmptyState = document.getElementById('claimEmptyState');
const claimSearch = document.getElementById('claimSearch');
const claimStatusFilter = document.getElementById('claimStatusFilter');
const claimOverlay = document.getElementById('claimModalOverlay');
const claimForm = document.getElementById('claimForm');

function renderClaims() {
  const query = claimSearch.value.trim().toLowerCase();
  const statusValue = claimStatusFilter.value;

  const filtered = allClaims.filter(c => {
    const patientName = (c.patient && c.patient.name) || '';
    const matchesQuery = patientName.toLowerCase().includes(query) || (c.invoiceRef || '').toLowerCase().includes(query);
    const matchesStatus = !statusValue || c.status === statusValue;
    return matchesQuery && matchesStatus;
  });

  claimTableBody.innerHTML = '';
  claimEmptyState.style.display = filtered.length === 0 ? 'block' : 'none';

  filtered.forEach(c => {
    const tr = document.createElement('tr');
    const refBadge = c.govtReferenceNumber
      ? `<div style="font-size:11px; opacity:0.7; margin-top:3px;">${c.submissionMode === 'SIMULATED' ? 'Sandbox ref (not sent to govt): ' : 'Govt ref: '}${c.govtReferenceNumber}</div>`
      : '';
    tr.innerHTML = `
      <td><div class="cell-person__name">${(c.patient && c.patient.name) || 'Unknown patient'}</div></td>
      <td>${(c.scheme && c.scheme.name) || '—'}</td>
      <td>${c.invoiceRef || '—'}</td>
      <td>${c.packageCode || '—'} &mdash; ${c.packageName || ''}</td>
      <td>${currency(c.claimedAmount)}</td>
      <td><span class="status-pill ${statusClass(c.status)}">${c.status}</span>${refBadge}</td>
      <td style="max-width:200px;">${c.remarks || '&mdash;'}</td>
    `;
    claimTableBody.appendChild(tr);
  });
}

function populateClaimEnrollmentSelect() {
  const select = document.getElementById('claimEnrollment');
  select.innerHTML = '';
  const verified = allEnrollments.filter(en => en.status === 'Verified');
  verified.forEach(en => {
    const opt = document.createElement('option');
    opt.value = en.id;
    opt.textContent = `${(en.patient && en.patient.name) || 'Unknown'} — ${(en.scheme && en.scheme.name) || ''} (${en.beneficiaryCardId || ''})`;
    select.appendChild(opt);
  });
  if (verified.length === 0) {
    const opt = document.createElement('option');
    opt.value = '';
    opt.textContent = 'No verified enrollments — add one and wait for Admin verification';
    select.appendChild(opt);
  }
}

document.getElementById('openClaimModal').addEventListener('click', () => {
  claimForm.reset();
  populateClaimEnrollmentSelect();
  claimOverlay.classList.add('is-open');
});
document.getElementById('closeClaimModal').addEventListener('click', () => claimOverlay.classList.remove('is-open'));
document.getElementById('cancelClaimModal').addEventListener('click', () => claimOverlay.classList.remove('is-open'));
claimOverlay.addEventListener('click', (e) => { if (e.target === claimOverlay) claimOverlay.classList.remove('is-open'); });

/** Monotonic-ish counter so sandbox reference numbers don't collide within a session. */
let sandboxRefCounter = Number(localStorage.getItem('lifetag_sandbox_ref_seq') || '1000');
function nextSandboxReference() {
  sandboxRefCounter += 1;
  localStorage.setItem('lifetag_sandbox_ref_seq', String(sandboxRefCounter));
  return `SANDBOX-${sandboxRefCounter}`;
}

/**
 * There is no live integration with Ayushman Bharat / PM-JAY or any other
 * scheme authority's portal, and there cannot be one in this build: a real
 * integration requires the hospital to be empanelled with the National
 * Health Authority (or the relevant state scheme authority) and issued
 * production API credentials. Clicking "Submit to government" does NOT
 * transmit anything to a government system — it records that reception has
 * finished the claim and marked it ready, timestamps that, and gives it a
 * clearly-labeled SANDBOX reference number. Filing on the real portal, and
 * recording what comes back, still has to happen outside this app — Admin
 * then records the *real* government reference number and outcome via the
 * claim review screen once it's known.
 */
function simulateGovernmentPortalSubmission() {
  const submittedAt = new Date().toISOString();
  return {
    status: 'Claim Submitted',
    mode: 'SIMULATED',
    referenceNumber: nextSandboxReference(),
    submittedAt,
    auditEntry: {
      at: submittedAt,
      event: 'Marked ready for government submission (simulated — not sent anywhere)',
      by: 'Reception'
    }
  };
}

claimForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const submitBtn = claimForm.querySelector('button[type="submit"]');
  const enrollmentId = Number(document.getElementById('claimEnrollment').value);
  const enrollment = allEnrollments.find(en => en.id === enrollmentId);
  if (!enrollment || enrollment.status !== 'Verified') {
    showToast('Only verified enrollments can be claimed against.');
    return;
  }

  const submission = simulateGovernmentPortalSubmission();
  const data = {
    enrollmentId,
    invoiceRef: document.getElementById('claimInvoiceRef').value.trim(),
    packageCode: document.getElementById('claimPackageCode').value.trim(),
    packageName: document.getElementById('claimPackageName').value.trim(),
    claimedAmount: Number(document.getElementById('claimAmount').value) || 0,
    status: submission.status,
    submissionMode: submission.mode,
    govtReferenceNumber: submission.referenceNumber,
    submittedDate: submission.submittedAt.slice(0, 10),
    auditTrailJson: JSON.stringify([submission.auditEntry])
  };

  submitBtn.disabled = true;
  try {
    await LifetagAPI.createSchemeClaim(data);
    logActivity('Created', 'Scheme Claim', `${(enrollment.patient && enrollment.patient.name) || 'Patient'} — ${data.invoiceRef}`);
    claimOverlay.classList.remove('is-open');
    allClaims = await LifetagAPI.getSchemeClaims();
    renderClaims();
    showToast(`Claim recorded as ready for submission (sandbox ref ${submission.referenceNumber}). File it on the official portal — this app doesn't submit it for you.`);
  } catch (err) {
    showToast(`Submission failed: ${err.message}`);
  } finally {
    submitBtn.disabled = false;
  }
});

claimSearch.addEventListener('input', renderClaims);
claimStatusFilter.addEventListener('change', renderClaims);

// ---------- Initial load ----------
(async function init() {
  try {
    [allPatients, allSchemes, allEnrollments, allClaims] = await Promise.all([
      LifetagAPI.getPatients(),
      LifetagAPI.getSchemes(),
      LifetagAPI.getPatientSchemes(),
      LifetagAPI.getSchemeClaims()
    ]);
    renderEnrollments();
    renderClaims();
  } catch (err) {
    showToast(`Couldn't load scheme data: ${err.message}`);
  }
})();


// Live check of the invoice and the amount while the claim is being filed (nothing is deducted until it is sanctioned)
(function () {
  const refresh = () => showClaimImpact(document.getElementById('claimImpact'), {
    invoiceRef: document.getElementById('claimInvoiceRef').value,
    amount: document.getElementById('claimAmount').value,
    policyId: null,
    approving: false
  });
  ['claimInvoiceRef', 'claimAmount', 'claimEnrollment'].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.addEventListener(id === 'claimInvoiceRef' ? 'change' : 'input', refresh);
  });
})();
