// ============================================
// INSURANCE — PATIENT VIEW (read-only), live data via LifetagAPI
//
// "Who am I" is resolved from the real session via GET /api/patients/me.
// A patient can see their own policy and claim status here, but cannot
// edit their policy or approve their own claim — that stays with
// Reception (policy details) and Admin (approve/reject), same reasoning
// that keeps a patient from self-approving a bill.
// ============================================

const policyPanel = document.getElementById('policyPanel');
const claimTableBody = document.getElementById('claimTableBody');
const emptyState = document.getElementById('emptyState');
const toast = document.getElementById('toast');
const toastMessage = document.getElementById('toastMessage');

function currency(n) { return '₹' + (Number(n) || 0).toLocaleString('en-IN'); }

function showToast(msg) {
  toastMessage.textContent = msg;
  toast.classList.add('is-visible');
  setTimeout(() => toast.classList.remove('is-visible'), 2400);
}

function claimStatusClass(status) { return (status || '').toLowerCase().replace(/\s+/g, ''); }

function renderPolicy(policies) {
  const policy = policies[0]; // most recent/only policy on file
  if (!policy) {
    policyPanel.innerHTML = `<div class="empty-state">No insurance details on file yet. Ask the front desk to add your policy.</div>`;
    return;
  }
  policyPanel.innerHTML = `
    <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(160px,1fr)); gap:20px; padding:20px;">
      <div>
        <div style="font-size:12px; color:var(--text-2); margin-bottom:4px;">Provider</div>
        <div style="font-weight:600;">${escapeHtml(policy.provider || '—')}</div>
      </div>
      <div>
        <div style="font-size:12px; color:var(--text-2); margin-bottom:4px;">Policy Number</div>
        <div style="font-weight:600;">${escapeHtml(policy.policyNumber || '—')}</div>
      </div>
      <div>
        <div style="font-size:12px; color:var(--text-2); margin-bottom:4px;">Coverage Type</div>
        <div style="font-weight:600;">${escapeHtml(policy.coverageType || '—')}</div>
      </div>
      <div>
        <div style="font-size:12px; color:var(--text-2); margin-bottom:4px;">Coverage Amount</div>
        <div style="font-weight:600;">${currency(policy.coverageAmount)}</div>
      </div>
      <div>
        <div style="font-size:12px; color:var(--text-2); margin-bottom:4px;">Valid Till</div>
        <div style="font-weight:600;">${escapeHtml(policy.validTo ? new Date(policy.validTo + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }) : '—')}</div>
      </div>
      <div>
        <div style="font-size:12px; color:var(--text-2); margin-bottom:4px;">Status</div>
        <span class="status-pill ${policy.status === 'Active' ? 'confirmed' : 'cancelled'}">${escapeHtml(policy.status)}</span>
      </div>
    </div>
  `;
}

function renderClaims(claims) {
  claimTableBody.innerHTML = '';
  emptyState.style.display = claims.length === 0 ? 'block' : 'none';

  claims.forEach(c => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${escapeHtml(c.invoiceRef || '—')}</td>
      <td>${currency(c.claimAmount)}</td>
      <td>${c.approvedAmount ? currency(c.approvedAmount) : '&mdash;'}</td>
      <td><span class="status-pill ${escapeHtml(claimStatusClass(c.status))}">${escapeHtml(c.status)}</span></td>
      <td style="max-width:220px;">${c.remarks ? escapeHtml(c.remarks) : '&mdash;'}</td>
      <td>
        <button class="btn-secondary download-btn" data-id="${escapeHtml(c.id)}" style="padding:6px 12px; font-size:12px;">Download Summary</button>
      </td>
    `;
    claimTableBody.appendChild(tr);
  });
}

claimTableBody.addEventListener('click', async (e) => {
  const btn = e.target.closest('.download-btn');
  if (!btn || btn.disabled) return;
  const label = btn.textContent;
  btn.disabled = true;
  btn.textContent = 'Preparing…';
  try {
    await LifetagAPI.downloadInsuranceClaimSummary(btn.dataset.id);
    showToast('Claim summary downloaded.');
  } catch (err) {
    showToast(err.message);
  } finally {
    btn.disabled = false;
    btn.textContent = label;
  }
});

(async function init() {
  let myProfile;
  try {
    myProfile = await LifetagAPI.getMyPatientProfile();
  } catch (err) {
    policyPanel.innerHTML = `<div class="empty-state">Couldn't load your profile: ${escapeHtml(err.message)}</div>`;
    return;
  }

  try {
    const [policies, claims] = await Promise.all([
      LifetagAPI.getInsuranceByPatient(myProfile.id),
      LifetagAPI.getInsuranceClaimsByPatient(myProfile.id)
    ]);
    renderPolicy(policies);
    renderClaims(claims);
  } catch (err) {
    policyPanel.innerHTML = `<div class="empty-state">Couldn't load insurance details: ${escapeHtml(err.message)}</div>`;
  }
})();
