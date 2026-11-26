// ============================================
// MENU MANAGEMENT (admin) — add / edit / delete sidebar entries stored in the
// menu_items table. Every action uses the record's numeric id; the server
// re-validates everything and only lets administrators in.
// ============================================

const ICON_KEYS = ['dashboard', 'patients', 'doctors', 'calendar', 'bed', 'blood', 'billing', 'shield', 'bank',
  'chart', 'feedback', 'clock', 'users', 'menu', 'audit', 'dot'];

const tableBody = document.getElementById('menuBody');
const emptyState = document.getElementById('emptyState');
const roleFilter = document.getElementById('roleFilter');
const overlay = document.getElementById('menuModalOverlay');
const form = document.getElementById('menuForm');
const formError = document.getElementById('formError');
let items = [];

ICON_KEYS.forEach((k) => {
  const o = document.createElement('option');
  o.value = k;
  o.textContent = k;
  document.getElementById('mIcon').appendChild(o);
});

function showToast(message) {
  document.getElementById('toastMessage').textContent = message;
  const t = document.getElementById('toast');
  t.classList.add('is-visible');
  setTimeout(() => t.classList.remove('is-visible'), 2600);
}

async function load() {
  try {
    items = await LifetagAPI.getAdminMenu();
    render();
  } catch (err) {
    emptyState.style.display = 'block';
    emptyState.textContent = `Couldn't load the menu: ${err.message}`;
  }
}

function render() {
  const rows = items.filter((i) => i.role === roleFilter.value);
  tableBody.innerHTML = '';
  emptyState.style.display = rows.length ? 'none' : 'block';
  emptyState.textContent = 'No menu items for this role.';
  rows.forEach((item) => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${escapeHtml(item.sortOrder)}</td>
      <td>${escapeHtml(item.groupLabel || '—')}</td>
      <td>${escapeHtml(item.label)}</td>
      <td>${escapeHtml(item.url)}</td>
      <td>${escapeHtml(item.icon || 'dot')}</td>
      <td>${item.active ? 'Shown' : 'Hidden'}</td>
      <td>
        <button type="button" class="btn-secondary" data-action="edit" data-id="${escapeHtml(item.id)}">Edit</button>
        <button type="button" class="btn-secondary" data-action="delete" data-id="${escapeHtml(item.id)}">Delete</button>
      </td>`;
    tableBody.appendChild(tr);
  });
}

// Event delegation: the record is identified by its id, never by its label or row position.
tableBody.addEventListener('click', async (e) => {
  const btn = e.target.closest('button[data-id]');
  if (!btn) return;
  const id = Number(btn.dataset.id);
  const item = items.find((i) => i.id === id);
  if (!item) return;
  if (btn.dataset.action === 'edit') return openModal(item);
  if (!confirm(`Delete the "${item.label}" menu entry for ${item.role}?`)) return;
  try {
    await LifetagAPI.deleteMenuItem(id);
    showToast('Menu item deleted.');
    load();
  } catch (err) {
    showToast(`Delete failed: ${err.message}`);
  }
});

function openModal(item) {
  form.reset();
  formError.textContent = '';
  document.getElementById('modalTitle').textContent = item ? 'Edit menu item' : 'Add menu item';
  document.getElementById('menuId').value = item ? item.id : '';
  document.getElementById('mRole').value = item ? item.role : roleFilter.value;
  document.getElementById('mGroup').value = item ? item.groupLabel || '' : '';
  document.getElementById('mLabel').value = item ? item.label : '';
  document.getElementById('mUrl').value = item ? item.url : '';
  document.getElementById('mIcon').value = item ? item.icon || 'dot' : 'dot';
  document.getElementById('mOrder').value = item ? item.sortOrder : 0;
  document.getElementById('mActive').value = item ? String(item.active) : 'true';
  overlay.classList.add('is-open');
}

function closeModal() { overlay.classList.remove('is-open'); }

document.getElementById('openAddModal').addEventListener('click', () => openModal(null));
document.getElementById('closeModal').addEventListener('click', closeModal);
document.getElementById('cancelModal').addEventListener('click', closeModal);
overlay.addEventListener('click', (e) => { if (e.target === overlay) closeModal(); });
roleFilter.addEventListener('change', render);

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  formError.textContent = '';
  const label = document.getElementById('mLabel').value.trim();
  const url = document.getElementById('mUrl').value.trim();
  const order = Number(document.getElementById('mOrder').value);
  // Front-end checks mirror the server's rules (the server is still the authority).
  if (!label) { formError.textContent = 'Label is required.'; return; }
  if (!/^[A-Za-z0-9_-]{1,60}\.html$/.test(url)) { formError.textContent = 'Page must be a file name like billing-payment.html.'; return; }
  if (!Number.isInteger(order) || order < 0 || order > 9999) { formError.textContent = 'Order must be a whole number from 0 to 9999.'; return; }

  const data = {
    role: document.getElementById('mRole').value,
    groupLabel: document.getElementById('mGroup').value.trim(),
    label,
    url,
    icon: document.getElementById('mIcon').value,
    sortOrder: order,
    active: document.getElementById('mActive').value === 'true',
  };
  const id = document.getElementById('menuId').value;
  try {
    if (id) await LifetagAPI.updateMenuItem(Number(id), data);
    else await LifetagAPI.createMenuItem(data);
    closeModal();
    showToast(id ? 'Menu item updated.' : 'Menu item added.');
    load();
  } catch (err) {
    formError.textContent = err.message;
  }
});

load();
