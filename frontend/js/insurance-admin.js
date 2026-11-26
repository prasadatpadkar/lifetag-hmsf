// ============================================
// INSURANCE — ADMIN VIEW, live data via LifetagAPI
// Admin's job here is narrow by design, mirroring the same separation of
// duties the rest of the app uses: manage the list of empanelled
// providers, and give final approve/reject authority on claims Reception
// has already submitted — same pattern as Admin holding refund authority
// in Billing.
// ============================================

let allProviders = [];
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

// ---------- Section tabs ----------
const sectionTabs = document.querySelectorAll('.section-tab');
const providersPanel = document.getElementById('providersPanel');
const claimsPanel = document.getElementById('claimsPanel');
sectionTabs.forEach(tab => {
  tab.addEventListener('click', () => {
    sectionTabs.forEach(t => t.classList.remove('is-active'));
    tab.classList.add('is-active');
    const section = tab.dataset.section;
    providersPanel.style.display = section === 'providers' ? 'block' : 'none';
    claimsPanel.style.display = section === 'claims' ? 'block' : 'none';
  });
});

// ============================================
// PROVIDERS
// ============================================
const provTableBody = document.getElementById('providerTableBody');
const provEmptyState = document.getElementById('providerEmptyState');
const provSearch = document.getElementById('provSearch');
const providerOverlay = document.getElementById('providerModalOverlay');
const providerForm = document.getElementById('providerForm');
const providerModalTitle = document.getElementById('providerModalTitle');
let editingProviderId = null;

function renderProviders() {
  const query = provSearch.value.trim().toLowerCase();
  const filtered = allProviders.filter(p => p.name.toLowerCase().includes(query));

  provTableBody.innerHTML = '';
  provEmptyState.style.display = filtered.length === 0 ? 'block' : 'none';

  filtered.forEach(p => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><div class="cell-person__name">${escapeHtml(p.name)}</div></td>
      <td>${escapeHtml(p.contact || '—')}</td>
      <td><span class="status-pill ${p.active ? 'confirmed' : 'cancelled'}">${p.active ? 'Active' : 'Inactive'}</span></td>
      <td>
        <div class="row-actions">
          <button class="edit-btn" data-id="${escapeHtml(p.id)}" aria-label="Edit">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>
          </button>
          <button class="delete-btn" data-id="${escapeHtml(p.id)}" aria-label="Delete">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>
          </button>
        </div>
      </td>
    `;
    provTableBody.appendChild(tr);
  });
}

function openProviderModal(mode, provider) {
  providerForm.reset();
  if (mode === 'edit' && provider) {
    editingProviderId = provider.id;
    providerModalTitle.textContent = 'Edit Provider';
    document.getElementById('providerName').value = provider.name;
    document.getElementById('providerContact').value = provider.contact || '';
    document.getElementById('providerStatus').value = String(provider.active);
  } else {
    editingProviderId = null;
    providerModalTitle.textContent = 'Add Provider';
    document.getElementById('providerStatus').value = 'true';
  }
  providerOverlay.classList.add('is-open');
}
function closeProviderModal() { providerOverlay.classList.remove('is-open'); }

document.getElementById('openProviderModal').addEventListener('click', () => openProviderModal('add'));
document.getElementById('closeProviderModal').addEventListener('click', closeProviderModal);
document.getElementById('cancelProviderModal').addEventListener('click', closeProviderModal);
providerOverlay.addEventListener('click', (e) => { if (e.target === providerOverlay) closeProviderModal(); });

providerForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const submitBtn = providerForm.querySelector('button[type="submit"]');
  const data = {
    name: document.getElementById('providerName').value.trim(),
    contact: document.getElementById('providerContact').value.trim(),
    active: document.getElementById('providerStatus').value === 'true'
  };

  submitBtn.disabled = true;
  try {
    if (editingProviderId) {
      await LifetagAPI.updateInsuranceProvider(editingProviderId, data);
      logActivity('Updated', 'Insurance Provider', data.name);
      showToast('Provider updated.');
    } else {
      await LifetagAPI.createInsuranceProvider(data);
      logActivity('Created', 'Insurance Provider', data.name);
      showToast('Provider added.');
    }
    closeProviderModal();
    allProviders = await LifetagAPI.getInsuranceProviders();
    renderProviders();
  } catch (err) {
    showToast(`Save failed: ${err.message}`);
  } finally {
    submitBtn.disabled = false;
  }
});

provTableBody.addEventListener('click', async (e) => {
  const editBtn = e.target.closest('.edit-btn');
  const deleteBtn = e.target.closest('.delete-btn');
  if (editBtn) {
    const p = allProviders.find(pr => pr.id === Number(editBtn.dataset.id));
    openProviderModal('edit', p);
  }
  if (deleteBtn) {
    if (confirm('Deactivate/remove this provider?')) {
      try {
        await LifetagAPI.deleteInsuranceProvider(Number(deleteBtn.dataset.id));
        logActivity('Deleted', 'Insurance Provider', 'Provider #' + deleteBtn.dataset.id);
        allProviders = await LifetagAPI.getInsuranceProviders();
        renderProviders();
        showToast('Provider removed.');
      } catch (err) {
        showToast(`Remove failed: ${err.message}`);
      }
    }
  }
});

provSearch.addEventListener('input', renderProviders);

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
    tr.innerHTML = `
      <td><div class="cell-person__name">${escapeHtml((c.patient && c.patient.name) || 'Unknown patient')}</div></td>
      <td>${escapeHtml((c.insurance && c.insurance.provider) || '—')}</td>
      <td>${escapeHtml(c.invoiceRef || '—')}</td>
      <td>${currency(c.claimAmount)}</td>
      <td>${c.approvedAmount ? currency(c.approvedAmount) : '&mdash;'}</td>
      <td><span class="status-pill ${escapeHtml(claimStatusClass(c.status))}">${escapeHtml(c.status)}</span></td>
      <td>
        <div class="row-actions">
          <button class="edit-btn review-btn" data-id="${escapeHtml(c.id)}" aria-label="Review">
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
    `<strong>${escapeHtml((c.patient && c.patient.name) || 'Unknown patient')}</strong> &middot; ${escapeHtml((c.insurance && c.insurance.provider) || '—')} &middot; Invoice ${escapeHtml(c.invoiceRef || '—')}<br>Claimed amount: ${currency(c.claimAmount)} &middot; Submitted ${escapeHtml(c.submittedDate || '—')}`;
  document.getElementById('approvedAmount').value = c.approvedAmount || '';
  document.getElementById('claimStatus').value = c.status;
  document.getElementById('claimRemarks').value = c.remarks || '';
  claimOverlay.classList.add('is-open');
  refreshImpact();
});

// Shows what approving this amount would do to the patient's bill (cover is deducted only on approval)
function refreshImpact() {
  const c = allClaims.find(cl => cl.id === reviewingClaimId);
  if (!c) return;
  showClaimImpact(document.getElementById('claimImpact'), {
    invoiceRef: c.invoiceRef,
    amount: document.getElementById('approvedAmount').value,
    policyId: c.insurance && c.insurance.id,
    approving: document.getElementById('claimStatus').value === 'Approved'
  });
}
document.getElementById('approvedAmount').addEventListener('input', refreshImpact);
document.getElementById('claimStatus').addEventListener('change', refreshImpact);

document.getElementById('closeClaimModal').addEventListener('click', () => claimOverlay.classList.remove('is-open'));
document.getElementById('cancelClaimModal').addEventListener('click', () => claimOverlay.classList.remove('is-open'));
claimOverlay.addEventListener('click', (e) => { if (e.target === claimOverlay) claimOverlay.classList.remove('is-open'); });

claimForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  if (!reviewingClaimId) return;
  const submitBtn = claimForm.querySelector('button[type="submit"]');

  const data = {
    approvedAmount: Number(document.getElementById('approvedAmount').value) || 0,
    status: document.getElementById('claimStatus').value,
    remarks: document.getElementById('claimRemarks').value.trim()
  };

  submitBtn.disabled = true;
  try {
    await LifetagAPI.decideInsuranceClaim(reviewingClaimId, data);
    const claim = allClaims.find(c => c.id === reviewingClaimId);
    logActivity('Updated', 'Insurance Claim', `${(claim && claim.patient && claim.patient.name) || 'Patient'} — ${data.status}`);
    claimOverlay.classList.remove('is-open');
    allClaims = await LifetagAPI.getInsuranceClaims();
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
    [allProviders, allClaims] = await Promise.all([
      LifetagAPI.getInsuranceProviders(),
      LifetagAPI.getInsuranceClaims()
    ]);
    renderProviders();
    renderClaims();
  } catch (err) {
    showToast(`Couldn't load insurance data: ${err.message}`);
  }
})();
