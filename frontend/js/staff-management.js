// ============================================
// STAFF ACCOUNTS — admin-only management of Reception-desk and Admin
// logins. There's no separate "Receptionist" entity: a receptionist is
// just a User with role RECEPTION (see StaffController on the backend).
// ============================================

let currentRole = 'RECEPTION';
let accounts = [];
let editingId = null;

const tableBody = document.getElementById('staffTableBody');
const emptyState = document.getElementById('emptyState');
const searchInput = document.getElementById('staffSearch');
const addBtnLabel = document.getElementById('addBtnLabel');
const tabButtons = document.querySelectorAll('.tab-btn');

const overlay = document.getElementById('staffModalOverlay');
const modalTitle = document.getElementById('modalTitle');
const form = document.getElementById('staffForm');
const enabledField = document.getElementById('enabledField');
const toast = document.getElementById('toast');
const toastMessage = document.getElementById('toastMessage');

function roleLabel(role) {
  return role === 'ADMIN' ? 'Admin' : 'Receptionist';
}

function initials(name) {
  return (name || '?').split(' ').filter(Boolean).map(w => w[0]).join('').slice(0, 2).toUpperCase();
}

function showToast(message) {
  toastMessage.textContent = message;
  toast.classList.add('is-visible');
  setTimeout(() => toast.classList.remove('is-visible'), 2600);
}

// ---------- Load ----------
async function loadAccounts() {
  tableBody.innerHTML = '';
  emptyState.style.display = 'none';
  try {
    accounts = currentRole === 'ADMIN' ? await LifetagAPI.getAdmins() : await LifetagAPI.getReceptionists();
    renderAccounts();
  } catch (err) {
    emptyState.style.display = 'block';
    emptyState.textContent = `Couldn't load accounts: ${err.message}`;
  }
}

// ---------- Render ----------
function renderAccounts() {
  const query = searchInput.value.trim().toLowerCase();
  const filtered = accounts.filter(a =>
    (a.fullName || '').toLowerCase().includes(query) ||
    (a.username || '').toLowerCase().includes(query) ||
    (a.email || '').toLowerCase().includes(query)
  );

  tableBody.innerHTML = '';
  if (filtered.length === 0) {
    emptyState.style.display = 'block';
    emptyState.textContent = `No ${roleLabel(currentRole).toLowerCase()} accounts found. Try a different search or add one.`;
    return;
  }
  emptyState.style.display = 'none';

  filtered.forEach(a => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>
        <div class="cell-person">
          <div class="cell-person__avatar">${escapeHtml(initials(a.fullName))}</div>
          <div>
            <div class="cell-person__name">${escapeHtml(a.fullName)}</div>
            <div class="cell-person__meta">${escapeHtml(roleLabel(a.role))}</div>
          </div>
        </div>
      </td>
      <td>${escapeHtml(a.username)}</td>
      <td>${escapeHtml(a.email)}</td>
      <td><span class="status-pill ${a.enabled ? 'onduty' : 'offduty'}">${a.enabled ? 'Active' : 'Deactivated'}</span></td>
      <td>
        <div class="row-actions">
          <button class="reset-pw-btn" data-id="${escapeHtml(a.id)}" aria-label="Reset password" title="Reset password">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12a9 9 0 1 0 3-6.7"/><path d="M3 4v5h5"/></svg>
          </button>
          <button class="edit-btn" data-id="${escapeHtml(a.id)}" aria-label="Edit">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>
          </button>
          <button class="delete-btn" data-id="${escapeHtml(a.id)}" aria-label="Remove access">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>
          </button>
        </div>
      </td>
    `;
    tableBody.appendChild(tr);
  });
}

// ---------- Tabs ----------
tabButtons.forEach(btn => {
  btn.addEventListener('click', () => {
    if (btn.dataset.role === currentRole) return;
    tabButtons.forEach(b => b.classList.remove('is-active'));
    btn.classList.add('is-active');
    currentRole = btn.dataset.role;
    addBtnLabel.textContent = `Add ${roleLabel(currentRole)}`;
    searchInput.value = '';
    loadAccounts();
  });
});

// ---------- Modal open/close ----------
function openModal(mode, account) {
  form.reset();
  if (mode === 'edit' && account) {
    editingId = account.id;
    modalTitle.textContent = `Edit ${roleLabel(account.role)}`;
    document.getElementById('fullName').value = account.fullName;
    document.getElementById('email').value = account.email;
    document.getElementById('enabled').value = String(account.enabled);
    enabledField.style.display = 'block';
  } else {
    editingId = null;
    modalTitle.textContent = `Add ${roleLabel(currentRole)}`;
    enabledField.style.display = 'none';
  }
  overlay.classList.add('is-open');
}

function closeModal() {
  overlay.classList.remove('is-open');
}

document.getElementById('openAddModal').addEventListener('click', () => openModal('add'));
document.getElementById('closeModal').addEventListener('click', closeModal);
document.getElementById('cancelModal').addEventListener('click', closeModal);
overlay.addEventListener('click', (e) => { if (e.target === overlay) closeModal(); });

// ---------- Save (add or edit) ----------
form.addEventListener('submit', async (e) => {
  e.preventDefault();

  const submitBtn = form.querySelector('button[type="submit"]');
  submitBtn.disabled = true;
  try {
    if (editingId) {
      const data = {
        fullName: document.getElementById('fullName').value.trim(),
        email: document.getElementById('email').value.trim(),
        enabled: document.getElementById('enabled').value === 'true'
      };
      await LifetagAPI.updateStaffAccount(editingId, data);
      showToast(`${roleLabel(currentRole)} account updated.`);
      logActivity('Updated', roleLabel(currentRole), data.fullName);
      closeModal();
    } else {
      const data = {
        fullName: document.getElementById('fullName').value.trim(),
        email: document.getElementById('email').value.trim()
      };
      const created = currentRole === 'ADMIN'
        ? await LifetagAPI.createAdmin(data)
        : await LifetagAPI.createReceptionist(data);
      showToast(`New ${roleLabel(currentRole).toLowerCase()} added.`);
      logActivity('Created', roleLabel(currentRole), data.fullName);
      closeModal();
      if (created && created.username && created.tempPassword) {
        showCredentialsModal({ role: roleLabel(currentRole).toLowerCase(), username: created.username, password: created.tempPassword });
      }
    }
    await loadAccounts();
  } catch (err) {
    showToast(`Save failed: ${err.message}`);
  } finally {
    submitBtn.disabled = false;
  }
});

// ---------- Edit / Reset password / Remove access (event delegation) ----------
tableBody.addEventListener('click', async (e) => {
  const editBtn = e.target.closest('.edit-btn');
  const deleteBtn = e.target.closest('.delete-btn');
  const resetBtn = e.target.closest('.reset-pw-btn');

  if (resetBtn) {
    const id = Number(resetBtn.dataset.id);
    const account = accounts.find(a => a.id === id);
    const confirmed = confirm(`Issue a new temporary password for ${account.fullName}? Their current password will stop working immediately.`);
    if (confirmed) {
      try {
        const updated = await LifetagAPI.resetStaffPassword(id);
        logActivity('Reset password', roleLabel(currentRole), account.fullName);
        if (updated && updated.username && updated.tempPassword) {
          showCredentialsModal({ role: roleLabel(currentRole).toLowerCase(), username: updated.username, password: updated.tempPassword });
        }
        await loadAccounts();
      } catch (err) {
        showToast(`Reset failed: ${err.message}`);
      }
    }
    return;
  }

  if (editBtn) {
    const id = Number(editBtn.dataset.id);
    const account = accounts.find(a => a.id === id);
    openModal('edit', account);
  }

  if (deleteBtn) {
    const id = Number(deleteBtn.dataset.id);
    const account = accounts.find(a => a.id === id);
    const confirmed = confirm(`Remove login access for ${account.fullName}? They won't be able to sign in anymore.`);
    if (confirmed) {
      try {
        await LifetagAPI.deactivateStaffAccount(id);
        showToast('Account access removed.');
        logActivity('Deleted', roleLabel(currentRole), account.fullName);
        await loadAccounts();
      } catch (err) {
        showToast(`Action failed: ${err.message}`);
      }
    }
  }
});

// ---------- Search ----------
searchInput.addEventListener('input', renderAccounts);

// ---------- Initial load ----------
loadAccounts();
