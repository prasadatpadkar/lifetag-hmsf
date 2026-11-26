// ============================================
// SHARED DASHBOARD BEHAVIOR
// Used by every module page (admin-dashboard.html,
// patient-management.html, etc.)
// ============================================

// Highlight the sidebar link matching the current page automatically
(function highlightActiveNav() {
  const currentPage = window.location.pathname.split('/').pop();
  document.querySelectorAll('.nav-link').forEach(link => {
    const linkPage = link.getAttribute('href');
    if (linkPage === currentPage) {
      document.querySelectorAll('.nav-link').forEach(l => l.classList.remove('is-active'));
      link.classList.add('is-active');
    }
  });
})();

// ============================================
// NOTIFICATIONS — real bell dropdown backed by /api/notifications.
// Works on every dashboard page since they all share this exact topbar
// markup (button.icon-btn[aria-label="Notifications"] with a .dot badge).
// ============================================
(function initNotifications() {
  const notifBtn = document.querySelector('.icon-btn[aria-label="Notifications"]');
  if (!notifBtn || typeof LifetagAPI === 'undefined' || !LifetagAPI.isLoggedIn()) return;

  // Wrap the existing button so we have a positioning anchor for the dropdown,
  // without having to touch the markup on all 28 pages that use this topbar.
  const wrapper = document.createElement('div');
  wrapper.className = 'notif-wrapper';
  notifBtn.parentNode.insertBefore(wrapper, notifBtn);
  wrapper.appendChild(notifBtn);

  const panel = document.createElement('div');
  panel.className = 'notif-panel';
  panel.hidden = true;
  panel.innerHTML = `
    <div class="notif-panel__header">
      <span>Notifications</span>
      <div class="notif-panel__header-actions">
        <button type="button" class="notif-panel__mark-all">Mark all read</button>
        <button type="button" class="notif-panel__clear-all">Clear all</button>
      </div>
    </div>
    <div class="notif-panel__list"><div class="notif-empty">Loading…</div></div>
  `;
  wrapper.appendChild(panel);

  const dot = notifBtn.querySelector('.dot');
  const listEl = panel.querySelector('.notif-panel__list');
  const markAllBtn = panel.querySelector('.notif-panel__mark-all');
  const clearAllBtn = panel.querySelector('.notif-panel__clear-all');
  let loaded = false;
  let unreadCount = 0;

  function updateDot() {
    if (dot) dot.style.display = unreadCount > 0 ? 'block' : 'none';
  }

  function timeAgo(iso) {
    const diffMs = Date.now() - new Date(iso).getTime();
    const mins = Math.floor(diffMs / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return `${mins}m ago`;
    const hrs = Math.floor(mins / 60);
    if (hrs < 24) return `${hrs}h ago`;
    const days = Math.floor(hrs / 24);
    return `${days}d ago`;
  }

  function render(notifications) {
    if (!notifications.length) {
      listEl.innerHTML = '<div class="notif-empty">You\'re all caught up.</div>';
      return;
    }
    listEl.innerHTML = notifications.map(n => `
      <div class="notif-item${n.read ? '' : ' is-unread'}" data-id="${escapeHtml(n.id)}" data-link="${n.link ? escapeHtml(n.link) : ''}">
        <div class="notif-item__top">
          <span class="notif-item__title">${escapeHtml(n.title)}</span>
          <div class="notif-item__top-right">
            <span class="notif-item__time">${escapeHtml(timeAgo(n.createdAt))}</span>
            <button type="button" class="notif-item__dismiss" data-dismiss-id="${escapeHtml(n.id)}" aria-label="Dismiss notification" title="Dismiss">&times;</button>
          </div>
        </div>
        ${n.message ? `<div class="notif-item__msg">${escapeHtml(n.message)}</div>` : ''}
      </div>
    `).join('');
  }

  async function refreshUnreadDot() {
    try {
      const { count } = await LifetagAPI.getUnreadNotificationCount();
      unreadCount = count;
      updateDot();
    } catch (err) {
      console.error('Failed to fetch unread notification count:', err);
    }
  }

  async function loadList() {
    listEl.innerHTML = '<div class="notif-empty">Loading…</div>';
    try {
      const notifications = await LifetagAPI.getNotifications();
      render(notifications);
      loaded = true;
    } catch (err) {
      console.error('Failed to load notifications:', err);
      listEl.innerHTML = `<div class="notif-empty">Couldn't load notifications. Check the console for details.</div>`;
    }
  }

  async function markAllReadSilently() {
    if (unreadCount === 0) return;
    const previouslyUnread = listEl.querySelectorAll('.notif-item.is-unread');
    previouslyUnread.forEach(el => el.classList.remove('is-unread'));
    unreadCount = 0;
    updateDot();
    try {
      await LifetagAPI.markAllNotificationsRead();
    } catch (err) {
      console.error('Failed to mark all notifications as read:', err);
      previouslyUnread.forEach(el => el.classList.add('is-unread'));
      refreshUnreadDot(); // resync the real count from the server
    }
  }

  notifBtn.addEventListener('click', async (e) => {
    e.stopPropagation();
    const willOpen = panel.hidden;
    panel.hidden = !willOpen;
    if (willOpen) {
      // Opening the bell is the "I've seen these" moment — clear the badge
      // now rather than waiting for the person to click each item.
      if (!loaded) await loadList();
      await markAllReadSilently();
    }
  });

  document.addEventListener('click', (e) => {
    if (!wrapper.contains(e.target)) panel.hidden = true;
  });

  listEl.addEventListener('click', async (e) => {
    const dismissBtn = e.target.closest('.notif-item__dismiss');
    if (dismissBtn) {
      e.stopPropagation();
      const item = dismissBtn.closest('.notif-item');
      const id = dismissBtn.dataset.dismissId;
      const wasUnread = item && item.classList.contains('is-unread');
      if (item) item.remove();
      if (wasUnread) {
        unreadCount = Math.max(0, unreadCount - 1);
        updateDot();
      }
      if (!listEl.querySelector('.notif-item')) {
        listEl.innerHTML = '<div class="notif-empty">You\'re all caught up.</div>';
      }
      try {
        await LifetagAPI.deleteNotification(id);
      } catch (err) {
        console.error('Failed to dismiss notification:', err);
        await loadList(); // resync with the server since the optimistic removal may be wrong
        await refreshUnreadDot();
      }
      return;
    }

    const item = e.target.closest('.notif-item');
    if (!item) return;
    const id = item.dataset.id;
    const link = item.dataset.link;
    if (item.classList.contains('is-unread')) {
      item.classList.remove('is-unread');
      // Update the bell badge immediately — don't wait on a second round-trip
      // to the unread-count endpoint before reflecting the click.
      unreadCount = Math.max(0, unreadCount - 1);
      updateDot();
      try {
        await LifetagAPI.markNotificationRead(id);
      } catch (err) {
        console.error('Failed to mark notification as read:', err);
        // Roll back the optimistic update so the UI matches the server.
        item.classList.add('is-unread');
        unreadCount += 1;
        updateDot();
      }
    }
    if (link) window.location.href = link;
  });

  markAllBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    markAllReadSilently();
  });

  clearAllBtn.addEventListener('click', async (e) => {
    e.stopPropagation();
    if (!listEl.querySelector('.notif-item')) return; // nothing to clear
    const confirmed = confirm("Clear all notifications? This can't be undone.");
    if (!confirmed) return;
    const previousHtml = listEl.innerHTML;
    listEl.innerHTML = '<div class="notif-empty">You\'re all caught up.</div>';
    unreadCount = 0;
    updateDot();
    try {
      await LifetagAPI.clearAllNotifications();
    } catch (err) {
      console.error('Failed to clear notifications:', err);
      listEl.innerHTML = previousHtml; // roll back so the UI matches the server
      refreshUnreadDot();
    }
  });

  refreshUnreadDot();
  setInterval(refreshUnreadDot, 20000);
})();

// ============================================
// RESPONSIVE SHELL (see css/responsive.css)
//  - <= 900px: sidebar becomes an off-canvas drawer opened from a menu button.
//  - Data tables get a scroll wrapper (tablet) and per-cell labels so CSS can
//    turn each row into a stacked card on phones. Rows are rendered later by
//    each page's script, so a MutationObserver keeps the labels up to date.
//  - --topbar-h tells the notification sheet where the top bar ends.
// All of it is a no-op on desktop.
// ============================================
(function setupResponsiveShell() {
  const dash = document.querySelector('.dash');
  const sidebar = document.querySelector('.sidebar');
  const topbar = document.querySelector('.topbar');
  if (!dash || !sidebar || !topbar) return;

  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'menu-btn';
  btn.setAttribute('aria-label', 'Open menu');
  btn.setAttribute('aria-expanded', 'false');
  btn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 6h16M4 12h16M4 18h16"/></svg>';
  topbar.insertBefore(btn, topbar.firstChild);

  const backdrop = document.createElement('div');
  backdrop.className = 'sidebar-backdrop';
  dash.appendChild(backdrop);

  function setOpen(open) {
    const wasOpen = dash.classList.contains('sidebar-open');
    dash.classList.toggle('sidebar-open', open);
    document.body.classList.toggle('sidebar-locked', open);
    btn.setAttribute('aria-expanded', String(open));
    btn.setAttribute('aria-label', open ? 'Close menu' : 'Open menu');
    if (open && !wasOpen) {
      const first = sidebar.querySelector('.nav-link.is-active') || sidebar.querySelector('.nav-link');
      if (first) setTimeout(() => first.focus({ preventScroll: true }), 50);
    } else if (!open && wasOpen) {
      btn.focus({ preventScroll: true });
    }
  }

  btn.addEventListener('click', () => setOpen(!dash.classList.contains('sidebar-open')));
  backdrop.addEventListener('click', () => setOpen(false));
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') setOpen(false); });
  // Leaving the phone layout (rotate / resize) must not leave the page scroll-locked.
  const desktopMq = window.matchMedia('(min-width: 901px)');
  const onMq = (e) => { if (e.matches) setOpen(false); };
  if (desktopMq.addEventListener) desktopMq.addEventListener('change', onMq);
  else if (desktopMq.addListener) desktopMq.addListener(onMq);

  // Swipe the open drawer to the left to close it.
  let touchX = null;
  sidebar.addEventListener('touchstart', (e) => { touchX = e.touches[0].clientX; }, { passive: true });
  sidebar.addEventListener('touchend', (e) => {
    if (touchX !== null && touchX - e.changedTouches[0].clientX > 60) setOpen(false);
    touchX = null;
  }, { passive: true });

  // Top bar height -> CSS variable (it changes with wrapping / font size / rotation).
  const setTopbarH = () => document.documentElement.style.setProperty('--topbar-h', topbar.offsetHeight + 'px');
  setTopbarH();
  if (window.ResizeObserver) new ResizeObserver(setTopbarH).observe(topbar);
  else window.addEventListener('resize', setTopbarH);

  // ---- Tables: scroll wrapper + data-label on each cell ----
  function labelRows(table) {
    const headRow = table.querySelector('thead tr');
    if (!headRow) return;
    const labels = Array.from(headRow.children).map((th) => th.textContent.trim());
    table.querySelectorAll('tbody tr').forEach((tr) => {
      let col = 0;
      Array.from(tr.children).forEach((td) => {
        if (td.colSpan > 1) { col += td.colSpan; return; }   // "No data" / error rows
        if (!td.hasAttribute('data-label')) td.setAttribute('data-label', labels[col] || '');
        col += 1;
      });
    });
  }

  document.querySelectorAll('table.data-table').forEach((table) => {
    if (!(table.parentElement && table.parentElement.classList.contains('table-scroll'))) {
      const wrap = document.createElement('div');
      wrap.className = 'table-scroll';
      table.parentNode.insertBefore(wrap, table);
      wrap.appendChild(table);
    }
    labelRows(table);
    let queued = false;
    new MutationObserver(() => {
      if (queued) return;
      queued = true;
      requestAnimationFrame(() => { queued = false; labelRows(table); });
    }).observe(table, { childList: true, subtree: true });
  });
})();

// Logout confirmation
const logoutLink = document.querySelector('.sidebar__footer .nav-link');
if (logoutLink) {
  logoutLink.addEventListener('click', (e) => {
    const confirmed = confirm('Are you sure you want to log out?');
    if (!confirmed) {
      e.preventDefault();
      return;
    }
    // Actually end the session: without this the saved token stays in the browser
    // and anyone can reopen any page URL as the signed-in user.
    try {
      // Tell the server too, so the token stops working everywhere (keepalive lets it finish after the page unloads).
      if (typeof API_BASE_URL !== 'undefined' && localStorage.getItem('lifetag_token')) {
        fetch(`${API_BASE_URL}/auth/logout`, {
          method: 'POST', keepalive: true,
          headers: { Authorization: `Bearer ${localStorage.getItem('lifetag_token')}` },
        }).catch(() => {});
      }
    } catch (err) { /* best effort */ }
    try {
      ['lifetag_token', 'lifetag_username', 'lifetag_role', 'lifetag_fullName', 'lifetag_current_user', 'lifetag_remember']
        .forEach((k) => localStorage.removeItem(k));
    } catch (err) { /* storage unavailable: nothing to clear */ }
  });
}

// ============================================
// CURRENT USER + ACTIVITY LOG
// Shared across every page so Admin can see what each
// Reception/Doctor staff member has done.
//
// Backed entirely by the server (GET/POST /api/staff-activity — see
// StaffActivityController) so it's a real audit trail shared across every
// device, not something that can be cleared or edited from devtools.
// ============================================

function getCurrentUser() {
  try {
    return JSON.parse(localStorage.getItem('lifetag_current_user')) || { name: 'Guest', role: 'Unknown' };
  } catch (e) {
    return { name: 'Guest', role: 'Unknown' };
  }
}

function logActivity(action, module, detail) {
  const user = getCurrentUser();
  // Fire-and-forget: a logging failure shouldn't block the action that triggered it.
  if (typeof LifetagAPI !== 'undefined' && LifetagAPI.isLoggedIn()) {
    LifetagAPI.logStaffActivity({
      staffName: user.name,
      role: user.role,
      action,   // 'Created' | 'Updated' | 'Deleted' | 'Reset password'
      details: `${module}: ${detail}`   // e.g. "Patient: Rohan Mehta"
    }).catch((err) => console.error('Failed to record staff activity:', err));
  }
}

// ============================================
// TOPBAR IDENTITY — shows the PERSON WHO ACTUALLY LOGGED IN,
// not a fixed demo name. Every dashboard-family page (admin,
// doctor, reception, patient) shares this same logic.
//
// Previously each portal script (doctor-portal.js, patient-portal.js)
// hardcoded its own "CURRENT_DOCTOR" / "CURRENT_PATIENT" constant and
// wrote it into the topbar chip, so no matter who typed in a different
// username at login, the same demo name ("Dr. Kavya Santos", "Rohan
// Mehta") kept showing up everywhere. This function reads the real
// value that script.js stored at login time (lifetag_current_user) and
// is the single place responsible for painting the topbar chip + page
// greeting, so it can never be silently overwritten by a later script.
// Once real authentication exists, source `user` from the session/JWT
// instead of localStorage.
// ============================================
function initialsOf(name) {
  if (!name) return '??';
  return name.replace(/^Dr\.\s*/i, '').trim().split(/\s+/).map(w => w[0]).join('').slice(0, 2).toUpperCase();
}

function populateIdentityChip() {
  const user = getCurrentUser();
  const name = user.name || 'Guest';

  // Admin
  const adminNameEl = document.getElementById('adminName');
  if (adminNameEl) {
    adminNameEl.textContent = name;
    const el = document.getElementById('adminInitials');
    if (el) el.textContent = initialsOf(name);
    const greet = document.getElementById('greeting');
    if (greet) greet.textContent = `Good morning, ${name.split(' ')[0]}`;
  }

  // Doctor
  const doctorNameEl = document.getElementById('doctorName');
  if (doctorNameEl) {
    const displayName = name.toLowerCase().startsWith('dr') ? name : `Dr. ${name}`;
    doctorNameEl.textContent = displayName;
    const el = document.getElementById('doctorInitials');
    if (el) el.textContent = initialsOf(displayName);
    const greet = document.getElementById('greeting');
    if (greet) greet.textContent = `Good morning, ${displayName.split(' ').pop()}`;
  }

  // Reception
  const receptionNameEl = document.getElementById('receptionName');
  if (receptionNameEl) {
    const displayName = name || 'Reception Staff';
    receptionNameEl.textContent = displayName;
    const el = document.getElementById('receptionInitials');
    if (el) el.textContent = initialsOf(displayName);
    const greet = document.getElementById('greeting');
    if (greet) greet.textContent = `Good morning, ${displayName.split(' ')[0]}`;
  }

  // Patient
  const patientNameEl = document.getElementById('patientName');
  if (patientNameEl) {
    patientNameEl.textContent = name;
    const idLabelEl = document.getElementById('patientIdLabel');
    if (idLabelEl) idLabelEl.textContent = 'Patient';
    const el = document.getElementById('patientInitials');
    if (el) el.textContent = initialsOf(name);
    const greet = document.getElementById('greeting');
    if (greet) greet.textContent = `Welcome back, ${name.split(' ')[0]}`;
  }
}

populateIdentityChip();

// ============================================
// CURRENT DATE — the dashboard subtitle ("Here's what's happening
// across Lifetag today — ...") used to be hardcoded text in the HTML,
// so it always showed the same fixed date no matter what day it
// actually was. This reads the real date from the browser instead.
// ============================================
function populateCurrentDate() {
  const el = document.getElementById('dateSubtitle');
  if (!el) return;
  const today = new Date().toLocaleDateString('en-IN', {
    day: 'numeric', month: 'long', year: 'numeric'
  });
  el.textContent = `Here's what's happening across Lifetag today — ${today}.`;
}
populateCurrentDate();

// ============================================
// ADMIN DASHBOARD — live stat cards, recent appointments, ward occupancy.
// Guarded so this only runs on admin-dashboard.html (the only page with
// these element ids); every other dashboard page skips it harmlessly.
// ============================================
async function loadAdminDashboard() {
  const statPatients = document.getElementById('statTotalPatients');
  if (!statPatients) return; // not on the admin dashboard

  try {
    const [summary, appointments, roomsBeds] = await Promise.all([
      LifetagAPI.getSummaryReport(),
      LifetagAPI.getAppointments(),
      LifetagAPI.getRoomsBeds()
    ]);

    statPatients.textContent = summary.totalPatients.toLocaleString('en-IN');
    document.getElementById('statTotalDoctors').textContent = summary.totalDoctors.toLocaleString('en-IN');
    const statReceptionists = document.getElementById('statTotalReceptionists');
    if (statReceptionists) statReceptionists.textContent = (summary.totalReceptionists || 0).toLocaleString('en-IN');
    const todayStr = new Date().toISOString().slice(0, 10);
    document.getElementById('statAppointmentsToday').textContent =
      appointments.filter(a => a.appointmentDate === todayStr).length.toLocaleString('en-IN');
    document.getElementById('statRevenue').textContent = '₹' + Number(summary.totalRevenueCollected || 0).toLocaleString('en-IN');

    // Recent appointments (soonest 5, upcoming first)
    const apptBody = document.getElementById('recentApptTableBody');
    const sorted = [...appointments].sort((a, b) =>
      `${a.appointmentDate || ''}${a.appointmentTime || ''}`.localeCompare(`${b.appointmentDate || ''}${b.appointmentTime || ''}`)
    ).slice(0, 5);

    apptBody.innerHTML = sorted.length ? sorted.map(a => {
      const patientName = (a.patient && a.patient.name) || 'Unknown patient';
      const doctorName = (a.doctor && a.doctor.name) || 'Unknown doctor';
      const time = (a.appointmentTime || '').toString().slice(0, 5);
      return `<tr><td>${escapeHtml(patientName)}</td><td>${escapeHtml(doctorName)}</td><td>${escapeHtml(time)}</td><td><span class="status-pill ${escapeHtml((a.status || '').toLowerCase())}">${escapeHtml(a.status)}</span></td></tr>`;
    }).join('') : '<tr><td colspan="4">No appointments yet.</td></tr>';

    // Ward occupancy (grouped by roomType)
    const wardList = document.getElementById('wardOccupancyList');
    const wards = ['General Ward', 'ICU', 'Maternity', 'Pediatric'];
    const rows = wards.map(ward => {
      const bedsInWard = roomsBeds.filter(b => b.roomType === ward);
      if (bedsInWard.length === 0) return '';
      const occupied = bedsInWard.filter(b => b.status === 'Occupied').length;
      const pct = Math.round((occupied / bedsInWard.length) * 100);
      const warnClass = pct >= 85 ? ' warn' : '';
      return `
        <div>
          <div class="ward-row__top"><span class="ward-row__name">${escapeHtml(ward)}</span><span class="ward-row__count">${occupied} / ${bedsInWard.length}</span></div>
          <div class="bar-track"><div class="bar-fill${warnClass}" style="width:${pct}%"></div></div>
        </div>`;
    }).join('');
    wardList.innerHTML = rows || '<p style="color:var(--slate); font-size:13px;">No beds configured yet.</p>';
  } catch (err) {
    document.getElementById('recentApptTableBody').innerHTML = `<tr><td colspan="4">Couldn't load dashboard data: ${escapeHtml(err.message)}</td></tr>`;
  }
}
loadAdminDashboard();

// ============================================
// RECEPTION DASHBOARD — live front-desk snapshot. Same idea as the admin
// one, guarded by element id so it only runs on reception-dashboard.html.
// Reads the same /api/appointments, /api/patients, /api/rooms-beds and
// /api/billing data that Admin, Doctor and Patient write to — which is what
// makes a patient's new booking show up here.
// ============================================
async function loadReceptionDashboard() {
  const statPatients = document.getElementById('statRecPatients');
  if (!statPatients) return; // not on the reception dashboard

  const apptBody = document.getElementById('recepApptBody');
  try {
    const [patients, appointments, roomsBeds, billings] = await Promise.all([
      LifetagAPI.getPatients(),
      LifetagAPI.getAppointments(),
      LifetagAPI.getRoomsBeds(),
      LifetagAPI.getBillings()
    ]);

    const todayStr = new Date().toISOString().slice(0, 10);
    const todays = appointments.filter(a => a.appointmentDate === todayStr);
    const pending = appointments.filter(a => a.status === 'Pending').length;

    statPatients.textContent = patients.length.toLocaleString('en-IN');
    document.getElementById('statRecApptsToday').textContent = todays.length.toLocaleString('en-IN');
    document.getElementById('statRecPending').textContent = `${pending} pending`;
    document.getElementById('statRecBeds').textContent =
      roomsBeds.filter(b => b.status === 'Available').length.toLocaleString('en-IN');

    // outstanding = bill - SANCTIONED insurance/scheme cover - paid (cancelled invoices owe nothing)
    const balanceOf = (b) => b.status === 'Cancelled' ? 0
      : Math.max(0, (Number(b.amount) || 0) - (Number(b.insuranceCovered) || 0) - (Number(b.amountPaid) || 0));
    const unpaid = billings.filter(b => balanceOf(b) > 0);
    const outstanding = unpaid.reduce((s, b) => s + balanceOf(b), 0);
    document.getElementById('statRecOutstanding').textContent = '₹' + Math.max(outstanding, 0).toLocaleString('en-IN');
    document.getElementById('statRecUnpaid').textContent = `${unpaid.length} unpaid`;

    // Today's appointments; if none today, show the next upcoming few so the table is never misleadingly empty.
    const byTime = (a, b) => `${a.appointmentDate || ''}${a.appointmentTime || ''}`.localeCompare(`${b.appointmentDate || ''}${b.appointmentTime || ''}`);
    const list = (todays.length ? todays : appointments.filter(a => (a.appointmentDate || '') >= todayStr)).sort(byTime).slice(0, 8);
    apptBody.innerHTML = list.length ? list.map(a => {
      const patientName = (a.patient && a.patient.name) || 'Unknown patient';
      const doctor = a.doctor ? `${a.doctor.name}${a.doctor.specialization ? ' · ' + a.doctor.specialization : ''}` : 'Unknown doctor';
      const time = (a.appointmentTime || '').toString().slice(0, 5);
      return `<tr><td>${escapeHtml(patientName)}</td><td>${escapeHtml(doctor)}</td><td>${escapeHtml(time)}</td><td><span class="status-pill ${escapeHtml((a.status || '').toLowerCase())}">${escapeHtml(a.status)}</span></td></tr>`;
    }).join('') : '<tr><td colspan="4">No appointments scheduled.</td></tr>';
  } catch (err) {
    apptBody.innerHTML = `<tr><td colspan="4">Couldn't load dashboard data: ${escapeHtml(err.message)}</td></tr>`;
  }
}
loadReceptionDashboard();

// ============================================
// CREDENTIALS MODAL — a small reusable helper other management scripts
// (doctors.js, patients.js, staff-management.js) call right after Admin/
// Reception creates a login-bearing account, so the one-time temporary
// password can be shown and copied. Lifetag never stores or re-displays
// it again — if it's lost, "Forgot password" on the login screen is the
// only way back in.
// ============================================
function showCredentialsModal({ role = 'account', username, password }) {
  if (!username || !password) return;

  let overlay = document.getElementById('credentialsModalOverlay');
  if (!overlay) {
    overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.id = 'credentialsModalOverlay';
    overlay.innerHTML = `
      <div class="modal" style="max-width:420px;">
        <div class="modal__head">
          <h2>Login details</h2>
          <button type="button" class="modal__close" id="credentialsModalClose" aria-label="Close">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6L6 18M6 6l12 12"/></svg>
          </button>
        </div>
        <p style="font-size:13px; color:var(--text-2); margin:0 0 16px;" id="credentialsModalIntro"></p>
        <div class="credential-box">
          <div class="credential-row">
            <span class="credential-row__label">Username</span>
            <code id="credentialsUsername"></code>
          </div>
          <div class="credential-row">
            <span class="credential-row__label">Temporary password</span>
            <code id="credentialsPassword"></code>
          </div>
        </div>
        <p style="font-size:12.5px; color:var(--text-2); margin:14px 0 0;">
          Share this with them directly. They should sign in with it, then use
          <strong>Forgot password</strong> on the login screen to set a password only they know.
          This won&rsquo;t be shown again.
        </p>
        <div class="modal__footer">
          <button type="button" class="btn-secondary" id="credentialsModalCopy">Copy details</button>
          <button type="button" class="btn-primary" style="width:auto; padding:10px 20px;" id="credentialsModalDone">Done</button>
        </div>
      </div>
    `;
    document.body.appendChild(overlay);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) overlay.classList.remove('is-open'); });
    overlay.querySelector('#credentialsModalClose').addEventListener('click', () => overlay.classList.remove('is-open'));
    overlay.querySelector('#credentialsModalDone').addEventListener('click', () => overlay.classList.remove('is-open'));
  }

  overlay.querySelector('#credentialsModalIntro').textContent = `The ${role} account is ready. Give them these sign-in details:`;
  overlay.querySelector('#credentialsUsername').textContent = username;
  overlay.querySelector('#credentialsPassword').textContent = password;

  const copyBtn = overlay.querySelector('#credentialsModalCopy');
  copyBtn.textContent = 'Copy details';
  copyBtn.onclick = async () => {
    const text = `Username: ${username}\nTemporary password: ${password}`;
    try {
      await navigator.clipboard.writeText(text);
      copyBtn.textContent = 'Copied!';
      setTimeout(() => { copyBtn.textContent = 'Copy details'; }, 1500);
    } catch (e) {
      // Clipboard API unavailable (e.g. insecure context) — details are still
      // visible on screen for the admin to note down manually.
    }
  };

  overlay.classList.add('is-open');
}

// ============================================
// TOP SEARCH BOX — filters the table rows / bed cards shown on the current page.
// (On pages with no list, e.g. dashboards, it points the user to a list page.)
// ============================================
(function () {
  const box = document.querySelector('.topbar__search input');
  if (!box) return;
  const originalPlaceholder = box.placeholder;
  const targets = () => document.querySelectorAll('.data-table tbody tr, .bed-card');

  function apply() {
    const q = box.value.trim().toLowerCase();
    const items = targets();
    if (q && items.length === 0) {
      box.value = '';
      box.placeholder = 'Open a list page (e.g. Patients) to search';
      return;
    }
    box.placeholder = originalPlaceholder;
    items.forEach((el) => {
      el.style.display = q && !el.textContent.toLowerCase().includes(q) ? 'none' : '';
    });
  }

  box.addEventListener('input', apply);
  box.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { box.value = ''; apply(); }
    if (e.key === 'Enter') e.preventDefault();
  });
  // Pages re-draw their tables after loading data; keep the filter applied to the new rows.
  const main = document.querySelector('.main');
  if (main) new MutationObserver(() => { if (box.value.trim()) apply(); }).observe(main, { childList: true, subtree: true });
})();
