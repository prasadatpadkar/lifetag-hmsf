// ============================================
// AUDIT LOG (admin) — read-only view of /api/audit-logs, filterable by
// user id, table, record id and operation. All values are escaped before display.
// ============================================

const TABLES = ['users', 'patients', 'doctors', 'appointments', 'billings', 'rooms_beds', 'blood_bank',
  'blood_requests', 'schemes', 'patient_schemes', 'insurance', 'insurance_claims', 'insurance_providers',
  'scheme_claims', 'feedback', 'menu_items'];

const body = document.getElementById('auditBody');
const emptyState = document.getElementById('emptyState');
const pageInfo = document.getElementById('pageInfo');
const prevBtn = document.getElementById('prevPage');
const nextBtn = document.getElementById('nextPage');
let page = 0;

TABLES.forEach((t) => {
  const o = document.createElement('option');
  o.value = t;
  o.textContent = t;
  document.getElementById('fTable').appendChild(o);
});

function shorten(json) {
  if (!json) return '—';
  return json.length > 160 ? json.slice(0, 160) + '…' : json;
}

async function load() {
  const params = {
    userId: document.getElementById('fUser').value.trim(),
    recordId: document.getElementById('fRecord').value.trim(),
    table: document.getElementById('fTable').value,
    operation: document.getElementById('fOp').value,
    page,
    size: 25,
  };
  body.innerHTML = '';
  emptyState.style.display = 'none';
  try {
    const result = await LifetagAPI.getAuditLogs(params);
    const rows = result.content || [];
    rows.forEach((r) => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td>${escapeHtml(new Date(r.createdAt).toLocaleString('en-IN'))}</td>
        <td>${escapeHtml(r.username || 'system')}${r.userId ? ' (#' + escapeHtml(r.userId) + ')' : ''}</td>
        <td>${escapeHtml(r.operation)}</td>
        <td>${escapeHtml(r.tableName)}</td>
        <td>${escapeHtml(r.recordId)}</td>
        <td><code title="${escapeHtml(r.oldValues || '')}">${escapeHtml(shorten(r.oldValues))}</code></td>
        <td><code title="${escapeHtml(r.newValues || '')}">${escapeHtml(shorten(r.newValues))}</code></td>`;
      body.appendChild(tr);
    });
    emptyState.style.display = rows.length ? 'none' : 'block';
    pageInfo.textContent = `Page ${result.number + 1} of ${Math.max(result.totalPages, 1)}`;
    prevBtn.disabled = result.first;
    nextBtn.disabled = result.last;
  } catch (err) {
    emptyState.style.display = 'block';
    emptyState.textContent = `Couldn't load the audit log: ${err.message}`;
  }
}

document.getElementById('auditFilters').addEventListener('submit', (e) => { e.preventDefault(); page = 0; load(); });
document.getElementById('fReset').addEventListener('click', () => {
  document.getElementById('auditFilters').reset();
  page = 0;
  load();
});
prevBtn.addEventListener('click', () => { if (page > 0) { page--; load(); } });
nextBtn.addEventListener('click', () => { page++; load(); });

load();
