// ============================================
// FEEDBACK — ADMIN VIEW, live data via LifetagAPI
// Shows every feedback entry (Admin sees all, per FeedbackController's
// role scoping), and lets Admin/Reception log feedback phoned in directly
// or correct a misattributed entry — patients submit their own from the
// patient portal (see patient-portal.js), which this list also picks up.
// ============================================

let feedbackList = [];
let allPatients = [];
let allDoctors = [];
let editingId = null;
let selectedRating = 5;

const tableBody = document.getElementById('fbTableBody');
const emptyState = document.getElementById('emptyState');
const searchInput = document.getElementById('fbSearch');
const ratingFilter = document.getElementById('ratingFilter');

const overlay = document.getElementById('fbModalOverlay');
const modalTitle = document.getElementById('modalTitle');
const form = document.getElementById('fbForm');
const toast = document.getElementById('toast');
const toastMessage = document.getElementById('toastMessage');
const ratingPicker = document.getElementById('ratingPicker');
const fbRatingInput = document.getElementById('fbRating');

const avgScore = document.getElementById('avgScore');
const avgStars = document.getElementById('avgStars');
const totalCount = document.getElementById('totalCount');
const distribution = document.getElementById('distribution');

// ---------- Star icon helpers ----------
const STAR_FILLED = `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87L18.18 21 12 17.77 5.82 21 7 14.14l-5-4.87 6.91-1.01L12 2z"/></svg>`;
const STAR_EMPTY = `<svg class="empty" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87L18.18 21 12 17.77 5.82 21 7 14.14l-5-4.87 6.91-1.01L12 2z"/></svg>`;

function starsHtml(rating) {
  let html = '';
  for (let i = 1; i <= 5; i++) html += i <= rating ? STAR_FILLED : STAR_EMPTY;
  return html;
}

function showToast(message) {
  toastMessage.textContent = message;
  toast.classList.add('is-visible');
  setTimeout(() => toast.classList.remove('is-visible'), 2600);
}

// ---------- Rating picker (modal) ----------
function renderRatingPicker() {
  ratingPicker.innerHTML = '';
  for (let i = 1; i <= 5; i++) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = i <= selectedRating ? 'is-filled' : '';
    btn.innerHTML = i <= selectedRating ? STAR_FILLED : STAR_EMPTY;
    btn.addEventListener('click', () => {
      selectedRating = i;
      fbRatingInput.value = i;
      renderRatingPicker();
    });
    ratingPicker.appendChild(btn);
  }
}

function populateSelects() {
  const patientSelect = document.getElementById('fbPatient');
  patientSelect.innerHTML = '';
  allPatients.forEach(p => {
    const opt = document.createElement('option');
    opt.value = p.id;
    opt.textContent = p.name;
    patientSelect.appendChild(opt);
  });

  const doctorSelect = document.getElementById('fbDoctor');
  doctorSelect.innerHTML = '<option value="">General / Not applicable</option>';
  allDoctors.forEach(d => {
    const opt = document.createElement('option');
    opt.value = d.id;
    opt.textContent = `${d.name} · ${d.specialization || 'General'}`;
    doctorSelect.appendChild(opt);
  });
}

// ---------- Summary + distribution ----------
function renderSummary() {
  if (feedbackList.length === 0) {
    avgScore.textContent = '0.0';
    avgStars.innerHTML = starsHtml(0);
    totalCount.textContent = '0 reviews total';
    distribution.innerHTML = '';
    return;
  }

  const total = feedbackList.length;
  const sum = feedbackList.reduce((s, f) => s + (f.rating || 0), 0);
  const avg = sum / total;

  avgScore.textContent = avg.toFixed(1);
  avgStars.innerHTML = starsHtml(Math.round(avg));
  totalCount.textContent = `${total} review${total !== 1 ? 's' : ''} total`;

  distribution.innerHTML = '';
  for (let star = 5; star >= 1; star--) {
    const count = feedbackList.filter(f => f.rating === star).length;
    const pct = total ? Math.round((count / total) * 100) : 0;
    const row = document.createElement('div');
    row.className = 'rating-bar-row';
    row.innerHTML = `
      <span style="width:34px;">${star} star</span>
      <div class="bar-track"><div class="bar-fill" style="width:${pct}%"></div></div>
      <span style="width:24px; text-align:right;">${count}</span>
    `;
    distribution.appendChild(row);
  }
}

// ---------- Render table ----------
function renderFeedback() {
  const query = searchInput.value.trim().toLowerCase();
  const ratingValue = ratingFilter.value;

  const filtered = feedbackList.filter(f => {
    const patientName = (f.patient && f.patient.name) || '';
    const doctorName = (f.doctor && f.doctor.name) || '';
    const matchesQuery = patientName.toLowerCase().includes(query) || doctorName.toLowerCase().includes(query);
    const matchesRating = !ratingValue || String(f.rating) === ratingValue;
    return matchesQuery && matchesRating;
  });

  tableBody.innerHTML = '';
  emptyState.style.display = filtered.length === 0 ? 'block' : 'none';

  filtered.forEach(f => {
    const patientName = (f.patient && f.patient.name) || 'Unknown patient';
    const doctorNameHtml = (f.doctor && f.doctor.name) ? escapeHtml(f.doctor.name) : '&mdash;';
    const dateStr = f.feedbackDate
      ? new Date(f.feedbackDate + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })
      : '—';
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><div class="cell-person__name">${escapeHtml(patientName)}</div></td>
      <td>${doctorNameHtml}</td>
      <td><div class="star-rating">${starsHtml(f.rating || 0)}</div></td>
      <td style="max-width:260px;">${f.comments ? escapeHtml(f.comments) : '&mdash;'}</td>
      <td>${escapeHtml(dateStr)}</td>
      <td>
        <div class="row-actions">
          <button class="edit-btn" data-id="${escapeHtml(f.id)}" aria-label="Edit">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>
          </button>
          <button class="delete-btn" data-id="${escapeHtml(f.id)}" aria-label="Delete">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>
          </button>
        </div>
      </td>
    `;
    tableBody.appendChild(tr);
  });

  renderSummary();
}

// ---------- Modal open/close ----------
function openModal(mode, fb) {
  form.reset();
  populateSelects();
  if (mode === 'edit' && fb) {
    editingId = fb.id;
    modalTitle.textContent = 'Edit Feedback';
    document.getElementById('fbPatient').value = fb.patient ? fb.patient.id : '';
    document.getElementById('fbDoctor').value = fb.doctor ? fb.doctor.id : '';
    document.getElementById('fbComment').value = fb.comments || '';
    selectedRating = fb.rating || 5;
  } else {
    editingId = null;
    modalTitle.textContent = 'Log Feedback';
    selectedRating = 5;
  }
  fbRatingInput.value = selectedRating;
  renderRatingPicker();
  overlay.classList.add('is-open');
}

function closeModal() {
  overlay.classList.remove('is-open');
}

document.getElementById('openAddModal').addEventListener('click', () => openModal('add'));
document.getElementById('closeModal').addEventListener('click', closeModal);
document.getElementById('cancelModal').addEventListener('click', closeModal);
overlay.addEventListener('click', (e) => { if (e.target === overlay) closeModal(); });

// ---------- Save ----------
form.addEventListener('submit', async (e) => {
  e.preventDefault();
  const submitBtn = form.querySelector('button[type="submit"]');
  const patientId = Number(document.getElementById('fbPatient').value);
  const patientName = (allPatients.find(p => p.id === patientId) || {}).name || 'Patient';
  const doctorId = document.getElementById('fbDoctor').value ? Number(document.getElementById('fbDoctor').value) : null;

  const data = {
    patientId,
    doctorId,
    rating: Number(fbRatingInput.value),
    comments: document.getElementById('fbComment').value.trim()
  };

  submitBtn.disabled = true;
  try {
    if (editingId) {
      await LifetagAPI.updateFeedback(editingId, data);
      logActivity('Updated', 'Feedback', patientName);
      showToast('Feedback updated.');
    } else {
      await LifetagAPI.createFeedback(data);
      logActivity('Created', 'Feedback', patientName);
      showToast('Feedback logged.');
    }
    closeModal();
    feedbackList = await LifetagAPI.getFeedback();
    renderFeedback();
  } catch (err) {
    showToast(`Save failed: ${err.message}`);
  } finally {
    submitBtn.disabled = false;
  }
});

// ---------- Edit / Delete ----------
tableBody.addEventListener('click', async (e) => {
  const editBtn = e.target.closest('.edit-btn');
  const deleteBtn = e.target.closest('.delete-btn');

  if (editBtn) {
    const fb = feedbackList.find(f => f.id === Number(editBtn.dataset.id));
    openModal('edit', fb);
  }

  if (deleteBtn) {
    const id = Number(deleteBtn.dataset.id);
    if (confirm('Delete this feedback entry?')) {
      try {
        await LifetagAPI.deleteFeedback(id);
        feedbackList = feedbackList.filter(f => f.id !== id);
        renderFeedback();
        showToast('Feedback deleted.');
      } catch (err) {
        showToast(`Delete failed: ${err.message}`);
      }
    }
  }
});

// ---------- Search & filter ----------
searchInput.addEventListener('input', renderFeedback);
ratingFilter.addEventListener('change', renderFeedback);

// ---------- Initial load ----------
(async function init() {
  try {
    [feedbackList, allPatients, allDoctors] = await Promise.all([
      LifetagAPI.getFeedback(),
      LifetagAPI.getPatients(),
      LifetagAPI.getDoctors()
    ]);
    renderFeedback();
  } catch (err) {
    showToast(`Couldn't load feedback: ${err.message}`);
  }
})();
