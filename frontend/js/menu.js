// ============================================
// SIDEBAR MENU — built from the database (menu_items table).
// The HTML of each page still contains a default sidebar; if the menu cannot be
// loaded (offline / API down) that default stays in place, so navigation never breaks.
// Admins edit the menu on pages/menu-management.html.
// ============================================

const MENU_ICONS = {
  dashboard: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="3" width="7" height="9" rx="1.5"/><rect x="14" y="3" width="7" height="5" rx="1.5"/><rect x="14" y="12" width="7" height="9" rx="1.5"/><rect x="3" y="16" width="7" height="5" rx="1.5"/></svg>',
  patients: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="8" r="3.5"/><path d="M5 21c0-4 3-6.5 7-6.5s7 2.5 7 6.5"/></svg>',
  doctors: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M9 3v4a3 3 0 0 0 6 0V3"/><path d="M6 8v3a6 6 0 0 0 12 0V8"/><path d="M12 17v4"/><circle cx="12" cy="21" r="0.6" fill="currentColor"/></svg>',
  calendar: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="4.5" width="18" height="16" rx="2"/><path d="M3 9.5h18"/><path d="M8 3v3M16 3v3"/><path d="M8 14l2.5 2.5L16 11"/></svg>',
  bed: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M3 18v-6a2 2 0 0 1 2-2h6v8"/><path d="M11 12h8a2 2 0 0 1 2 2v4"/><path d="M3 18h18"/><circle cx="7" cy="8" r="1.6"/></svg>',
  blood: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 3c3.5 4 6 7.2 6 10.2A6 6 0 0 1 6 13.2C6 10.2 8.5 7 12 3Z"/></svg>',
  billing: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 10h18"/><path d="M7 14.5h4"/></svg>',
  shield: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 3l7 3v6c0 4.5-3 8-7 9-4-1-7-4.5-7-9V6l7-3Z"/><path d="M9.3 12.2l1.8 1.8L15 10"/></svg>',
  bank: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M3 21h18"/><path d="M4 21V10"/><path d="M20 21V10"/><path d="M2 10l10-6 10 6"/><path d="M8 21v-7"/><path d="M12 21v-7"/><path d="M16 21v-7"/></svg>',
  chart: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 19V9"/><path d="M11 19V5"/><path d="M18 19v-7"/><path d="M3 19h18"/></svg>',
  feedback: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M21 12a8 8 0 1 1-3.4-6.5"/><path d="M21 4v5h-5"/></svg>',
  clock: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/></svg>',
  users: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="9" cy="8" r="3"/><path d="M2.5 20c0-3.5 3-6 6.5-6s6.5 2.5 6.5 6"/><path d="M16.5 5.5c1.4.3 2.5 1.6 2.5 3.1 0 1.6-1.1 2.9-2.6 3.2"/><path d="M19 14.5c2 .5 3.5 2.2 3.5 4.3"/></svg>',
  menu: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 6h16M4 12h16M4 18h10"/></svg>',
  audit: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M6 3h9l4 4v14H6z"/><path d="M14 3v5h5"/><path d="M9 13h7M9 17h7"/></svg>',
  records: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M6 3h9l4 4v14H6z"/><path d="M14 3v5h5"/><path d="M12 11v6M9 14h6"/></svg>',
  dot: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="3"/></svg>'
};

async function buildSidebarMenu() {
  const sidebar = document.querySelector('.sidebar');
  if (!sidebar || typeof LifetagAPI === 'undefined' || !LifetagAPI.isLoggedIn()) return;

  let items;
  try {
    items = await LifetagAPI.getMyMenu();
  } catch (err) {
    return; // keep the default sidebar
  }
  if (!Array.isArray(items) || items.length === 0) return;

  const current = window.location.pathname.split('/').pop();
  const footer = sidebar.querySelector('.sidebar__footer');
  const frag = document.createDocumentFragment();
  let lastGroup = null;
  let list = null;

  items.forEach((item) => {
    const group = item.groupLabel || '';
    if (list === null || group !== lastGroup) {
      if (group) {
        const label = document.createElement('div');
        label.className = 'nav-group-label';
        label.textContent = group;
        frag.appendChild(label);
      }
      list = document.createElement('ul');
      list.className = 'nav-list';
      frag.appendChild(list);
      lastGroup = group;
    }
    const li = document.createElement('li');
    const a = document.createElement('a');
    a.href = item.url;
    a.className = 'nav-link' + (item.url === current ? ' is-active' : '');
    a.insertAdjacentHTML('beforeend', MENU_ICONS[item.icon] || MENU_ICONS.dot); // icon markup is a fixed local constant
    a.appendChild(document.createTextNode(' ' + item.label));
    li.appendChild(a);
    list.appendChild(li);
  });

  sidebar.querySelectorAll('.nav-group-label, ul.nav-list').forEach((n) => n.remove());
  sidebar.insertBefore(frag, footer);
}

buildSidebarMenu();
