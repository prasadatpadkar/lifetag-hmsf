// ============================================
// INSURANCE — RECEPTION VIEW, live data via LifetagAPI
// Reception captures policy details and files claims at the same
// touchpoint where they already register patients and generate bills.
// They can SUBMIT and TRACK claims, but final approve/reject authority
// stays with Admin (same pattern as refunds staying with Admin in Billing).
// ============================================

let allPatients = [];
let allProviders = [];
let allPolicies = [];
let allClaims = [];

function currency(n) { return '₹' + (Number(n) || 0).toLocaleString('en-IN'); }
function claimStatusClass(status) { return (status || '').toLowerCase().replace(/\s+/g, ''); }

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

function populateProviderSelect(selectEl, includeInactive) {
  selectEl.innerHTML = '';
  allProviders.filter(p => includeInactive || p.active).forEach(p => {
    const opt = document.createElement('option');
    opt.value = p.id;
    opt.textContent = p.name;
    selectEl.appendChild(opt);
  });
}

// ---------- Section tabs ----------
const sectionTabs = document.querySelectorAll('.section-tab');
const policiesPanel = document.getElementById('policiesPanel');
const claimsPanel = document.getElementById('claimsPanel');
sectionTabs.forEach(tab => {
  tab.addEventListener('click', () => {
    sectionTabs.forEach(t => t.classList.remove('is-active'));
    tab.classList.add('is-active');
    const section = tab.dataset.section;
    policiesPanel.style.display = section === 'policies' ? 'block' : 'none';
    claimsPanel.style.display = section === 'claims' ? 'block' : 'none';
  });
});

// ============================================
// PATIENT INSURANCE
// ============================================
const policyTableBody = document.getElementById('policyTableBody');
const policyEmptyState = document.getElementById('policyEmptyState');
const policySearch = document.getElementById('policySearch');
const policyOverlay = document.getElementById('policyModalOverlay');
const policyForm = document.getElementById('policyForm');
const policyModalTitle = document.getElementById('policyModalTitle');
let editingPolicyId = null;

function renderPolicies() {
  const query = policySearch.value.trim().toLowerCase();
  const filtered = allPolicies.filter(p => (p.patient && p.patient.name || '').toLowerCase().includes(query));

  policyTableBody.innerHTML = '';
  policyEmptyState.style.display = filtered.length === 0 ? 'block' : 'none';

  filtered.forEach(p => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><div class="cell-person__name">${escapeHtml((p.patient && p.patient.name) || 'Unknown patient')}</div></td>
      <td>${escapeHtml(p.provider || '—')}</td>
      <td>${escapeHtml(p.policyNumber || '—')}</td>
      <td>${currency(p.coverageAmount)}</td>
      <td>${escapeHtml(p.validTo ? new Date(p.validTo + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }) : '—')}</td>
      <td><span class="status-pill ${p.status === 'Active' ? 'confirmed' : 'cancelled'}">${escapeHtml(p.status)}</span></td>
      <td>
        <div class="row-actions">
          <button class="edit-btn" data-id="${escapeHtml(p.id)}" aria-label="Edit">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>
          </button>
        </div>
      </td>
    `;
    policyTableBody.appendChild(tr);
  });
}

function openPolicyModal(mode, policy) {
  policyForm.reset();
  populatePatientSelect(document.getElementById('policyPatient'));
  populateProviderSelect(document.getElementById('policyProvider'), true);
  if (mode === 'edit' && policy) {
    editingPolicyId = policy.id;
    policyModalTitle.textContent = 'Edit Patient Insurance';
    document.getElementById('policyPatient').value = policy.patient ? policy.patient.id : '';
    document.getElementById('policyProvider').value = allProviders.find(pr => pr.name === policy.provider)?.id || '';
    document.getElementById('policyNumber').value = policy.policyNumber || '';
    document.getElementById('policyCoverageType').value = policy.coverageType || 'Individual';
    document.getElementById('policyCoverageAmount').value = policy.coverageAmount || '';
    document.getElementById('policyValidTill').value = policy.validTo || '';
    document.getElementById('policyStatus').value = policy.status || 'Active';
  } else {
    editingPolicyId = null;
    policyModalTitle.textContent = 'Add Patient Insurance';
    document.getElementById('policyStatus').value = 'Active';
  }
  policyOverlay.classList.add('is-open');
}
function closePolicyModal() { policyOverlay.classList.remove('is-open'); }

document.getElementById('openPolicyModal').addEventListener('click', () => openPolicyModal('add'));
document.getElementById('closePolicyModal').addEventListener('click', closePolicyModal);
document.getElementById('cancelPolicyModal').addEventListener('click', closePolicyModal);
policyOverlay.addEventListener('click', (e) => { if (e.target === policyOverlay) closePolicyModal(); });

policyForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const submitBtn = policyForm.querySelector('button[type="submit"]');
  const providerId = Number(document.getElementById('policyProvider').value);
  const providerName = (allProviders.find(p => p.id === providerId) || {}).name || '';
  const patientId = Number(document.getElementById('policyPatient').value);
  const patientName = (allPatients.find(p => p.id === patientId) || {}).name || '';

  const data = {
    patientId,
    provider: providerName,
    policyNumber: document.getElementById('policyNumber').value.trim(),
    coverageType: document.getElementById('policyCoverageType').value,
    coverageAmount: Number(document.getElementById('policyCoverageAmount').value) || 0,
    validTo: document.getElementById('policyValidTill').value,
    status: document.getElementById('policyStatus').value
  };

  submitBtn.disabled = true;
  try {
    if (editingPolicyId) {
      await LifetagAPI.updateInsurance(editingPolicyId, data);
      logActivity('Updated', 'Patient Insurance', patientName);
      showToast('Insurance details updated.');
    } else {
      await LifetagAPI.createInsurance(data);
      logActivity('Created', 'Patient Insurance', patientName);
      showToast('Insurance details added.');
    }
    closePolicyModal();
    allPolicies = await LifetagAPI.getInsurance();
    renderPolicies();
  } catch (err) {
    showToast(`Save failed: ${err.message}`);
  } finally {
    submitBtn.disabled = false;
  }
});

policyTableBody.addEventListener('click', (e) => {
  const editBtn = e.target.closest('.edit-btn');
  if (!editBtn) return;
  const p = allPolicies.find(pl => pl.id === Number(editBtn.dataset.id));
  openPolicyModal('edit', p);
});

policySearch.addEventListener('input', renderPolicies);

// ============================================
// CLAIMS (submit + track — no approve/reject here)
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
    tr.innerHTML = `
      <td><div class="cell-person__name">${escapeHtml((c.patient && c.patient.name) || 'Unknown patient')}</div></td>
      <td>${escapeHtml((c.insurance && c.insurance.provider) || '—')}</td>
      <td>${escapeHtml(c.invoiceRef || '—')}</td>
      <td>${currency(c.claimAmount)}</td>
      <td><span class="status-pill ${escapeHtml(claimStatusClass(c.status))}">${escapeHtml(c.status)}</span></td>
      <td style="max-width:220px;">${c.remarks ? escapeHtml(c.remarks) : '&mdash;'}</td>
    `;
    claimTableBody.appendChild(tr);
  });
}

function populateClaimPolicySelect() {
  const select = document.getElementById('claimPolicy');
  select.innerHTML = '';
  const active = allPolicies.filter(p => p.status === 'Active');
  active.forEach(p => {
    const opt = document.createElement('option');
    opt.value = p.id;
    opt.textContent = `${(p.patient && p.patient.name) || 'Unknown'} — ${p.provider || ''} (${p.policyNumber || ''})`;
    select.appendChild(opt);
  });
  if (active.length === 0) {
    const opt = document.createElement('option');
    opt.value = '';
    opt.textContent = 'No active patient insurance on file — add one first';
    select.appendChild(opt);
  }
}

document.getElementById('openClaimModal').addEventListener('click', () => {
  claimForm.reset();
  populateClaimPolicySelect();
  claimOverlay.classList.add('is-open');
});
document.getElementById('closeClaimModal').addEventListener('click', () => claimOverlay.classList.remove('is-open'));
document.getElementById('cancelClaimModal').addEventListener('click', () => claimOverlay.classList.remove('is-open'));
claimOverlay.addEventListener('click', (e) => { if (e.target === claimOverlay) claimOverlay.classList.remove('is-open'); });

claimForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const submitBtn = claimForm.querySelector('button[type="submit"]');
  const policyId = Number(document.getElementById('claimPolicy').value);
  const policy = allPolicies.find(p => p.id === policyId);
  if (!policy) { showToast('Add patient insurance details first.'); return; }

  const data = {
    policyId,
    invoiceRef: document.getElementById('claimInvoiceRef').value.trim(),
    claimAmount: Number(document.getElementById('claimAmount').value) || 0
  };

  submitBtn.disabled = true;
  try {
    await LifetagAPI.createInsuranceClaim(data);
    logActivity('Created', 'Insurance Claim', `${(policy.patient && policy.patient.name) || 'Patient'} — ${data.invoiceRef}`);
    claimOverlay.classList.remove('is-open');
    allClaims = await LifetagAPI.getInsuranceClaims();
    renderClaims();
    showToast('Claim submitted for Admin review.');
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
    [allPatients, allProviders, allPolicies, allClaims] = await Promise.all([
      LifetagAPI.getPatients(),
      LifetagAPI.getInsuranceProviders(),
      LifetagAPI.getInsurance(),
      LifetagAPI.getInsuranceClaims()
    ]);
    renderPolicies();
    renderClaims();
  } catch (err) {
    showToast(`Couldn't load insurance data: ${err.message}`);
  }
})();


// Live check of the invoice and the amount while the claim is being filed (nothing is deducted until it is sanctioned)
(function () {
  const refresh = () => showClaimImpact(document.getElementById('claimImpact'), {
    invoiceRef: document.getElementById('claimInvoiceRef').value,
    amount: document.getElementById('claimAmount').value,
    policyId: document.getElementById('claimPolicy').value,
    approving: false
  });
  ['claimInvoiceRef', 'claimAmount', 'claimPolicy'].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.addEventListener(id === 'claimInvoiceRef' ? 'change' : 'input', refresh);
  });
})();
