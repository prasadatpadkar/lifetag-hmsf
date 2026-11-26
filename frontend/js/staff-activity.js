// ============================================
// STAFF ACTIVITY LOG — live data via LifetagAPI (see js/api-config.js)
// Server-side audit log (every logActivity() call in dashboard.js writes here).
// ============================================

let log = [];

const tableBody = document.getElementById('logTableBody');
const searchInput = document.getElementById('logSearch');
const roleFilter = document.getElementById('roleFilter');
const moduleFilter = document.getElementById('moduleFilter');
const clearLogBtn = document.getElementById('clearLogBtn');

function formatTimestamp(ts) {
  if (!ts) return '—';
  const d = new Date(ts);
  return d.toLocaleString('en-IN', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });
}

async function loadLog() {
  try {
    log = await LifetagAPI.getStaffActivity();
    log.sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp));
    render();
  } catch (err) {
    tableBody.innerHTML = `<tr><td colspan="5">Couldn't load activity log: ${escapeHtml(err.message)}</td></tr>`;
  }
}

function render() {
  const query = searchInput.value.trim().toLowerCase();
  const roleValue = roleFilter.value;
  const moduleValue = moduleFilter.value;

  const filtered = log.filter(entry => {
    const details = entry.details || '';
    const matchesQuery = (entry.staffName || '').toLowerCase().includes(query) || details.toLowerCase().includes(query);
    const matchesRole = !roleValue || entry.role === roleValue;
    const matchesModule = !moduleValue || details.startsWith(moduleValue);
    return matchesQuery && matchesRole && matchesModule;
  });

  tableBody.innerHTML = '';

  if (filtered.length === 0) {
    tableBody.innerHTML = '<tr><td colspan="5">No matching activity.</td></tr>';
    return;
  }

  filtered.forEach(entry => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${escapeHtml(entry.staffName || 'Unknown')}</td>
      <td>${escapeHtml(entry.role || '—')}</td>
      <td>${escapeHtml(entry.action || '—')}</td>
      <td>${escapeHtml(entry.details || '—')}</td>
      <td>${escapeHtml(formatTimestamp(entry.timestamp))}</td>
    `;
    tableBody.appendChild(tr);
  });
}

searchInput.addEventListener('input', render);
roleFilter.addEventListener('change', render);
moduleFilter.addEventListener('change', render);

// The backend only exposes GET/POST for this audit log (no DELETE, by design —
// an audit trail shouldn't be erasable from the UI). Keep the button as a
// client-side view filter instead of destructive data loss.
if (clearLogBtn) {
  clearLogBtn.textContent = 'Refresh';
  clearLogBtn.addEventListener('click', loadLog);
}

loadLog();
