// ============================================
// DOCTOR FEEDBACK (view-only) — live data via LifetagAPI
//
// "Who am I" is resolved from the real session via GET /api/doctors/me,
// then feedback is filtered to entries left for this doctor specifically.
// A doctor can't edit or delete a patient's feedback here — this page
// only ever reads it.
// ============================================

const tableBody = document.getElementById('fbTableBody');
const emptyState = document.getElementById('emptyState');
const searchInput = document.getElementById('fbSearch');
const ratingFilter = document.getElementById('ratingFilter');
const avgScore = document.getElementById('avgScore');
const avgStars = document.getElementById('avgStars');
const totalCount = document.getElementById('totalCount');
const distribution = document.getElementById('distribution');

const STAR_FILLED = `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87L18.18 21 12 17.77 5.82 21 7 14.14l-5-4.87 6.91-1.01L12 2z"/></svg>`;
const STAR_EMPTY = `<svg class="empty" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87L18.18 21 12 17.77 5.82 21 7 14.14l-5-4.87 6.91-1.01L12 2z"/></svg>`;

let myFeedback = [];

function starsHtml(rating) {
  let html = '';
  for (let i = 1; i <= 5; i++) html += i <= rating ? STAR_FILLED : STAR_EMPTY;
  return html;
}

function renderSummary() {
  if (myFeedback.length === 0) {
    avgScore.textContent = '0.0';
    avgStars.innerHTML = starsHtml(0);
    totalCount.textContent = '0 reviews total';
    distribution.innerHTML = '';
    return;
  }
  const total = myFeedback.length;
  const sum = myFeedback.reduce((s, f) => s + (f.rating || 0), 0);
  const avg = sum / total;

  avgScore.textContent = avg.toFixed(1);
  avgStars.innerHTML = starsHtml(Math.round(avg));
  totalCount.textContent = `${total} review${total !== 1 ? 's' : ''} total`;

  distribution.innerHTML = '';
  for (let star = 5; star >= 1; star--) {
    const count = myFeedback.filter(f => f.rating === star).length;
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

function renderFeedback() {
  const query = searchInput.value.trim().toLowerCase();
  const ratingValue = ratingFilter.value;

  const filtered = myFeedback.filter(f => {
    const patientName = (f.patient && f.patient.name) || '';
    const matchesQuery = patientName.toLowerCase().includes(query);
    const matchesRating = !ratingValue || String(f.rating) === ratingValue;
    return matchesQuery && matchesRating;
  });

  tableBody.innerHTML = '';
  emptyState.style.display = filtered.length === 0 ? 'block' : 'none';

  filtered.forEach(f => {
    const patientName = (f.patient && f.patient.name) || 'Unknown patient';
    const dateStr = f.feedbackDate
      ? new Date(f.feedbackDate + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })
      : '—';
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><div class="cell-person__name">${escapeHtml(patientName)}</div></td>
      <td><div class="star-rating">${starsHtml(f.rating || 0)}</div></td>
      <td style="max-width:320px;">${f.comments ? escapeHtml(f.comments) : '&mdash;'}</td>
      <td>${escapeHtml(dateStr)}</td>
    `;
    tableBody.appendChild(tr);
  });

  renderSummary();
}

(async function init() {
  let myDoctorProfile;
  try {
    myDoctorProfile = await LifetagAPI.getMyDoctorProfile();
  } catch (err) {
    emptyState.style.display = 'block';
    emptyState.textContent = `Couldn't load your profile: ${err.message}`;
    return;
  }

  try {
    const allFeedback = await LifetagAPI.getFeedback();
    myFeedback = allFeedback.filter(f => f.doctor && f.doctor.id === myDoctorProfile.id);
  } catch (err) {
    emptyState.style.display = 'block';
    emptyState.textContent = `Couldn't load feedback: ${err.message}`;
    return;
  }

  searchInput.addEventListener('input', renderFeedback);
  ratingFilter.addEventListener('change', renderFeedback);

  renderFeedback();
})();
