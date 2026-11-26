// ============================================
// GOVERNMENT SCHEMES — PATIENT VIEW (read-only), live data via LifetagAPI
//
// "Who am I" is resolved from the real session via GET /api/patients/me.
// A patient can see their own enrollment (Aadhaar always masked) and claim
// status here, but cannot edit enrollment details or approve their own
// claim — that stays with Reception (capturing details) and Admin
// (verify + approve/reject).
// ============================================

const enrollPanel = document.getElementById('enrollPanel');
const claimTableBody = document.getElementById('claimTableBody');
const emptyState = document.getElementById('emptyState');
const toast = document.getElementById('toast');
const toastMessage = document.getElementById('toastMessage');

function currency(n) { return '₹' + (Number(n) || 0).toLocaleString('en-IN'); }
function statusClass(status) { return (status || '').toLowerCase().replace(/\s+/g, ''); }

// Aadhaar is regulated, sensitive government ID data — never render the
// full number anywhere in the UI, only this masked form.
function maskAadhaar(number) {
  const digits = String(number || '').replace(/\D/g, '');
  if (digits.length < 4) return '••••-••••-••••';
  return `••••-••••-${digits.slice(-4)}`;
}

function showToast(msg) {
  toastMessage.textContent = msg;
  toast.classList.add('is-visible');
  setTimeout(() => toast.classList.remove('is-visible'), 2400);
}

function renderEnrollment(enrollments) {
  const enrollment = enrollments[0];
  if (!enrollment) {
    enrollPanel.innerHTML = `<div class="empty-state">No scheme enrollment on file yet. Ask the front desk to enroll you in an applicable government scheme.</div>`;
    return;
  }
  const schemeName = (enrollment.scheme && enrollment.scheme.name) || '—';
  enrollPanel.innerHTML = `
    <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(160px,1fr)); gap:20px; padding:20px;">
      <div>
        <div style="font-size:12px; color:var(--text-2); margin-bottom:4px;">Scheme</div>
        <div style="font-weight:600;">${schemeName}</div>
      </div>
      <div>
        <div style="font-size:12px; color:var(--text-2); margin-bottom:4px;">Aadhaar Number</div>
        <div style="font-weight:600;">${maskAadhaar(enrollment.aadhaarNumber)}</div>
      </div>
      <div>
        <div style="font-size:12px; color:var(--text-2); margin-bottom:4px;">Beneficiary Card ID</div>
        <div style="font-weight:600;">${enrollment.beneficiaryCardId || '—'}</div>
      </div>
      <div>
        <div style="font-size:12px; color:var(--text-2); margin-bottom:4px;">Eligibility Status</div>
        <span class="status-pill ${statusClass(enrollment.status)}">${enrollment.status}</span>
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
      <td>${c.invoiceRef || '—'}</td>
      <td>${c.packageCode || '—'} &mdash; ${c.packageName || ''}</td>
      <td>${currency(c.claimedAmount)}</td>
      <td>${c.sanctionedAmount ? currency(c.sanctionedAmount) : '&mdash;'}</td>
      <td><span class="status-pill ${statusClass(c.status)}">${c.status}</span></td>
      <td>
        ${c.status === 'Approved'
          ? `<button class="btn-secondary download-btn" data-id="${c.id}" style="padding:6px 12px; font-size:12px;">Download Sanction Letter</button>`
          : `<span style="font-size:12px; color:var(--text-2);">Available once approved</span>`}
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
    await LifetagAPI.downloadSchemeSanctionLetter(btn.dataset.id);
    showToast('Sanction letter downloaded.');
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
    enrollPanel.innerHTML = `<div class="empty-state">Couldn't load your profile: ${err.message}</div>`;
    return;
  }

  try {
    const [enrollments, claims] = await Promise.all([
      LifetagAPI.getPatientSchemesByPatient(myProfile.id),
      LifetagAPI.getSchemeClaimsByPatient(myProfile.id)
    ]);
    renderEnrollment(enrollments);
    renderClaims(claims);
  } catch (err) {
    enrollPanel.innerHTML = `<div class="empty-state">Couldn't load scheme details: ${err.message}</div>`;
  }
})();
