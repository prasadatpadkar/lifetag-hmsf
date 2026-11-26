// ============================================
// GOVERNMENT SCHEMES — ADMIN VIEW, live data via LifetagAPI
// Same separation-of-duties pattern as Insurance, plus one extra gate:
// Admin holds VERIFICATION authority (confirming a patient's Aadhaar/
// beneficiary card details are genuine and they actually qualify) in
// addition to final approve/reject authority on claims. Reception can
// capture enrollment details and submit claims, but cannot self-certify
// eligibility or approve its own submission.
// ============================================

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

// ---------- Section tabs ----------
const sectionTabs = document.querySelectorAll('.section-tab');
const schemesPanel = document.getElementById('schemesPanel');
const enrollmentsPanel = document.getElementById('enrollmentsPanel');
const claimsPanel = document.getElementById('claimsPanel');
sectionTabs.forEach(tab => {
  tab.addEventListener('click', () => {
    sectionTabs.forEach(t => t.classList.remove('is-active'));
    tab.classList.add('is-active');
    const section = tab.dataset.section;
    schemesPanel.style.display = section === 'schemes' ? 'block' : 'none';
    enrollmentsPanel.style.display = section === 'enrollments' ? 'block' : 'none';
    claimsPanel.style.display = section === 'claims' ? 'block' : 'none';
  });
});

// ============================================
// SCHEMES (lookup list)
// ============================================
const schemeTableBody = document.getElementById('schemeTableBody');
const schemeEmptyState = document.getElementById('schemeEmptyState');
const schemeSearch = document.getElementById('schemeSearch');
const schemeOverlay = document.getElementById('schemeModalOverlay');
const schemeForm = document.getElementById('schemeForm');
const schemeModalTitle = document.getElementById('schemeModalTitle');
let editingSchemeId = null;

function renderSchemes() {
  const query = schemeSearch.value.trim().toLowerCase();
  const filtered = allSchemes.filter(s => s.name.toLowerCase().includes(query));

  schemeTableBody.innerHTML = '';
  schemeEmptyState.style.display = filtered.length === 0 ? 'block' : 'none';

  filtered.forEach(s => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><div class="cell-person__name">${s.name}</div></td>
      <td>${s.eligibility || s.category || '—'}</td>
      <td>${s.benefits ? currency(s.benefits) : '—'}</td>
      <td><span class="status-pill ${s.status === 'Active' ? 'confirmed' : 'cancelled'}">${s.status}</span></td>
      <td>
        <div class="row-actions">
          <button class="edit-btn" data-id="${s.id}" aria-label="Edit">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>
          </button>
          <button class="delete-btn" data-id="${s.id}" aria-label="Delete">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>
          </button>
        </div>
      </td>
    `;
    schemeTableBody.appendChild(tr);
  });
}

function openSchemeModal(mode, scheme) {
  schemeForm.reset();
  if (mode === 'edit' && scheme) {
    editingSchemeId = scheme.id;
    schemeModalTitle.textContent = 'Edit Scheme';
    document.getElementById('schemeName').value = scheme.name;
    document.getElementById('schemeAuthority').value = scheme.eligibility || scheme.category || '';
    document.getElementById('schemeCoverageLimit').value = scheme.benefits || '';
    document.getElementById('schemeStatus').value = scheme.status === 'Active' ? 'true' : 'false';
  } else {
    editingSchemeId = null;
    schemeModalTitle.textContent = 'Add Scheme';
    document.getElementById('schemeStatus').value = 'true';
  }
  schemeOverlay.classList.add('is-open');
}
function closeSchemeModal() { schemeOverlay.classList.remove('is-open'); }

document.getElementById('openSchemeModal').addEventListener('click', () => openSchemeModal('add'));
document.getElementById('closeSchemeModal').addEventListener('click', closeSchemeModal);
document.getElementById('cancelSchemeModal').addEventListener('click', closeSchemeModal);
schemeOverlay.addEventListener('click', (e) => { if (e.target === schemeOverlay) closeSchemeModal(); });

schemeForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const submitBtn = schemeForm.querySelector('button[type="submit"]');
  const data = {
    name: document.getElementById('schemeName').value.trim(),
    eligibility: document.getElementById('schemeAuthority').value.trim(),
    benefits: document.getElementById('schemeCoverageLimit').value.trim(),
    status: document.getElementById('schemeStatus').value === 'true' ? 'Active' : 'Inactive'
  };

  submitBtn.disabled = true;
  try {
    if (editingSchemeId) {
      await LifetagAPI.updateScheme(editingSchemeId, data);
      logActivity('Updated', 'Government Scheme', data.name);
      showToast('Scheme updated.');
    } else {
      await LifetagAPI.createScheme(data);
      logActivity('Created', 'Government Scheme', data.name);
      showToast('Scheme added.');
    }
    closeSchemeModal();
    allSchemes = await LifetagAPI.getSchemes();
    renderSchemes();
  } catch (err) {
    showToast(`Save failed: ${err.message}`);
  } finally {
    submitBtn.disabled = false;
  }
});

schemeTableBody.addEventListener('click', async (e) => {
  const editBtn = e.target.closest('.edit-btn');
  const deleteBtn = e.target.closest('.delete-btn');
  if (editBtn) {
    const s = allSchemes.find(sc => sc.id === Number(editBtn.dataset.id));
    openSchemeModal('edit', s);
  }
  if (deleteBtn) {
    if (confirm('Deactivate/remove this scheme?')) {
      try {
        await LifetagAPI.deleteScheme(Number(deleteBtn.dataset.id));
        logActivity('Deleted', 'Government Scheme', 'Scheme #' + deleteBtn.dataset.id);
        allSchemes = await LifetagAPI.getSchemes();
        renderSchemes();
        showToast('Scheme removed.');
      } catch (err) {
        showToast(`Remove failed: ${err.message}`);
      }
    }
  }
});

schemeSearch.addEventListener('input', renderSchemes);

// ============================================
// ENROLLMENTS (verification gate — unique to Schemes)
// A claim cannot be submitted against an enrollment until Admin has
// explicitly verified the Aadhaar/beneficiary card details here.
// ============================================
const enrollTableBody = document.getElementById('enrollTableBody');
const enrollEmptyState = document.getElementById('enrollEmptyState');
const enrollSearch = document.getElementById('enrollSearch');
const enrollStatusFilter = document.getElementById('enrollStatusFilter');
const verifyOverlay = document.getElementById('verifyModalOverlay');
const verifyForm = document.getElementById('verifyForm');
let verifyingEnrollmentId = null;

function renderEnrollments() {
  const query = enrollSearch.value.trim().toLowerCase();
  const statusValue = enrollStatusFilter.value;

  const filtered = allEnrollments.filter(en => {
    const matchesQuery = ((en.patient && en.patient.name) || '').toLowerCase().includes(query);
    const matchesStatus = !statusValue || en.status === statusValue;
    return matchesQuery && matchesStatus;
  });

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
          <button class="edit-btn verify-btn" data-id="${en.id}" aria-label="Verify">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7Z"/><circle cx="12" cy="12" r="3"/></svg>
          </button>
        </div>
      </td>
    `;
    enrollTableBody.appendChild(tr);
  });
}

enrollTableBody.addEventListener('click', (e) => {
  const btn = e.target.closest('.verify-btn');
  if (!btn) return;
  const en = allEnrollments.find(e2 => e2.id === Number(btn.dataset.id));
  verifyingEnrollmentId = en.id;
  document.getElementById('verifySummary').innerHTML =
    `<strong>${(en.patient && en.patient.name) || 'Unknown patient'}</strong> &middot; ${(en.scheme && en.scheme.name) || '—'}<br>Aadhaar: ${maskAadhaar(en.aadhaarNumber)} &middot; Beneficiary card: ${en.beneficiaryCardId || '—'}<br>Submitted ${en.enrollmentDate || '—'}`;
  document.getElementById('verifyStatus').value = en.status;
  verifyOverlay.classList.add('is-open');
});

document.getElementById('closeVerifyModal').addEventListener('click', () => verifyOverlay.classList.remove('is-open'));
document.getElementById('cancelVerifyModal').addEventListener('click', () => verifyOverlay.classList.remove('is-open'));
verifyOverlay.addEventListener('click', (e) => { if (e.target === verifyOverlay) verifyOverlay.classList.remove('is-open'); });

verifyForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  if (!verifyingEnrollmentId) return;
  const submitBtn = verifyForm.querySelector('button[type="submit"]');
  const newStatus = document.getElementById('verifyStatus').value;

  submitBtn.disabled = true;
  try {
    await LifetagAPI.verifyPatientScheme(verifyingEnrollmentId, newStatus);
    const en = allEnrollments.find(e2 => e2.id === verifyingEnrollmentId);
    logActivity('Updated', 'Scheme Enrollment', `${(en && en.patient && en.patient.name) || 'Patient'} — ${newStatus}`);
    verifyOverlay.classList.remove('is-open');
    allEnrollments = await LifetagAPI.getPatientSchemes();
    renderEnrollments();
    showToast('Eligibility decision saved.');
  } catch (err) {
    showToast(`Save failed: ${err.message}`);
  } finally {
    submitBtn.disabled = false;
  }
});

enrollSearch.addEventListener('input', renderEnrollments);
enrollStatusFilter.addEventListener('change', renderEnrollments);

// ============================================
// CLAIMS (approve / reject — final authority)
// ============================================
const claimTableBody = document.getElementById('claimTableBody');
const claimEmptyState = document.getElementById('claimEmptyState');
const claimSearch = document.getElementById('claimSearch');
const claimStatusFilter = document.getElementById('claimStatusFilter');
const claimOverlay = document.getElementById('claimModalOverlay');
const claimForm = document.getElementById('claimForm');
let reviewingClaimId = null;

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
      <td>${c.sanctionedAmount ? currency(c.sanctionedAmount) : '&mdash;'}</td>
      <td><span class="status-pill ${statusClass(c.status)}">${c.status}</span>${refBadge}</td>
      <td>
        <div class="row-actions">
          <button class="edit-btn review-btn" data-id="${c.id}" aria-label="Review">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7Z"/><circle cx="12" cy="12" r="3"/></svg>
          </button>
        </div>
      </td>
    `;
    claimTableBody.appendChild(tr);
  });
}

claimTableBody.addEventListener('click', (e) => {
  const btn = e.target.closest('.review-btn');
  if (!btn) return;
  const c = allClaims.find(cl => cl.id === Number(btn.dataset.id));
  reviewingClaimId = c.id;
  document.getElementById('claimSummary').innerHTML =
    `<strong>${(c.patient && c.patient.name) || 'Unknown patient'}</strong> &middot; ${(c.scheme && c.scheme.name) || '—'} &middot; Invoice ${c.invoiceRef || '—'}<br>Package: ${c.packageCode || '—'} &mdash; ${c.packageName || ''}<br>Claimed amount: ${currency(c.claimedAmount)} &middot; Submitted ${c.submittedDate || '—'}`;
  document.getElementById('sanctionedAmount').value = c.sanctionedAmount || '';
  document.getElementById('claimStatus').value = c.status;
  document.getElementById('govtReferenceNumber').value = c.govtReferenceNumber || '';
  document.getElementById('claimRemarks').value = c.remarks || '';
  claimOverlay.classList.add('is-open');
  refreshImpact();
});

// Shows what sanctioning this amount would do to the patient's bill (cover is deducted only on approval)
function refreshImpact() {
  const c = allClaims.find(cl => cl.id === reviewingClaimId);
  if (!c) return;
  const max = c.scheme && c.scheme.maxCoverAmount;
  showClaimImpact(document.getElementById('claimImpact'), {
    invoiceRef: c.invoiceRef,
    amount: document.getElementById('sanctionedAmount').value,
    approving: document.getElementById('claimStatus').value === 'Approved'
  });
  if (max) {
    const el = document.getElementById('claimImpact');
    setTimeout(() => { el.innerHTML += `<br>Scheme fixed limit: ₹${Number(max).toLocaleString('en-IN')} per family per year.`; }, 400);
  }
}
document.getElementById('sanctionedAmount').addEventListener('input', refreshImpact);
document.getElementById('claimStatus').addEventListener('change', refreshImpact);

document.getElementById('closeClaimModal').addEventListener('click', () => claimOverlay.classList.remove('is-open'));
document.getElementById('cancelClaimModal').addEventListener('click', () => claimOverlay.classList.remove('is-open'));
claimOverlay.addEventListener('click', (e) => { if (e.target === claimOverlay) claimOverlay.classList.remove('is-open'); });

claimForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  if (!reviewingClaimId) return;
  const submitBtn = claimForm.querySelector('button[type="submit"]');
  const claim = allClaims.find(c => c.id === reviewingClaimId);

  const enteredRef = document.getElementById('govtReferenceNumber').value.trim();
  // A reference the admin actually typed in (different from the sandbox one reception's
  // submission generated) means someone filed this on the real portal and got a real
  // reference back — record that so the UI stops calling it "sandbox".
  const isRealRef = enteredRef && claim && enteredRef !== claim.govtReferenceNumber;
  const newStatus = document.getElementById('claimStatus').value;

  const trail = (() => {
    try { return JSON.parse((claim && claim.auditTrailJson) || '[]'); } catch (e) { return []; }
  })();
  trail.push({
    at: new Date().toISOString(),
    event: `Reviewed by admin — status set to "${newStatus}"${isRealRef ? ', real government reference recorded' : ''}`,
    by: (getCurrentUser().name || 'Admin')
  });

  const data = {
    sanctionedAmount: Number(document.getElementById('sanctionedAmount').value) || 0,
    status: newStatus,
    govtReferenceNumber: enteredRef,
    submissionMode: isRealRef ? 'GOVERNMENT' : (claim ? claim.submissionMode : undefined),
    remarks: document.getElementById('claimRemarks').value.trim(),
    auditTrailJson: JSON.stringify(trail)
  };

  submitBtn.disabled = true;
  try {
    await LifetagAPI.decideSchemeClaim(reviewingClaimId, data);
    logActivity('Updated', 'Scheme Claim', `${(claim && claim.patient && claim.patient.name) || 'Patient'} — ${newStatus}`);
    claimOverlay.classList.remove('is-open');
    allClaims = await LifetagAPI.getSchemeClaims();
    renderClaims();
    showToast('Claim decision saved.');
  } catch (err) {
    showToast(`Save failed: ${err.message}`);
  } finally {
    submitBtn.disabled = false;
  }
});

claimSearch.addEventListener('input', renderClaims);
claimStatusFilter.addEventListener('change', renderClaims);

// ---------- Initial load ----------
(async function init() {
  try {
    [allSchemes, allEnrollments, allClaims] = await Promise.all([
      LifetagAPI.getSchemes(),
      LifetagAPI.getPatientSchemes(),
      LifetagAPI.getSchemeClaims()
    ]);
    renderSchemes();
    renderEnrollments();
    renderClaims();
  } catch (err) {
    showToast(`Couldn't load scheme data: ${err.message}`);
  }
})();
