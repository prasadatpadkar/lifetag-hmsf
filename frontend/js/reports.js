// ============================================
// REPORTS & ANALYTICS — live data via LifetagAPI (see js/api-config.js)
// KPIs come from /api/reports/summary; the charts are derived client-side
// from the raw module endpoints (appointments, billing, blood bank, doctors,
// feedback) since there's no separate aggregate endpoint per chart.
// ============================================

const brand = {
  teal: '#3B52DB',
  tealLight: 'rgba(59,82,219,0.15)',
  coral: '#C1352A',
  coralLight: 'rgba(193,53,42,0.15)',
  navy: '#3A4752',
  slate: '#5C7079',
  gold: '#C99700',
  paper: '#DDE2DD'
};

function currency(n) { return '₹' + Math.round(n || 0).toLocaleString('en-IN'); }

let latestExportLines = [];

function showError(message) {
  const main = document.querySelector('main.content') || document.querySelector('main');
  if (!main) {
    alert(message); // last-resort fallback so this is never silently swallowed
    return;
  }
  const banner = document.createElement('div');
  banner.style.cssText = 'background:#F6E3E0; color:#9B2A20; border-left:6px solid #C1352A; border-radius:0; padding:12px 16px; margin-bottom:16px; font-size:14px;';
  banner.textContent = message;
  main.insertBefore(banner, main.firstChild);
}

// Chart.js loads from a CDN (see the <script> tag in reports-analytics.html) — if it's
// blocked by an ad-blocker, a corporate firewall, or just fails to fetch, `Chart` won't
// exist. Configure Chart.js defaults only if it actually loaded, and surface a clear
// message immediately instead of letting the whole script silently crash and leave the
// page showing its unchanged placeholder HTML.
const chartJsAvailable = typeof Chart !== 'undefined';
if (chartJsAvailable) {
  Chart.defaults.font.family = "'IBM Plex Sans', sans-serif";
  Chart.defaults.color = brand.slate;
  Chart.defaults.font.size = 12;
} else {
  document.addEventListener('DOMContentLoaded', () => {
    showError("Couldn't load the charting library (Chart.js) from the CDN — check your internet connection, or whether an ad-blocker/firewall is blocking cdnjs.cloudflare.com. The KPI numbers below will still load.");
  });
}

async function loadReports() {
  try {
    const [summary, appointments, billings, bloodBank, doctors, feedback] = await Promise.all([
      LifetagAPI.getSummaryReport(),
      LifetagAPI.getAppointments(),
      LifetagAPI.getBillings(),
      LifetagAPI.getBloodBank(),
      LifetagAPI.getDoctors(),
      LifetagAPI.getFeedback().catch(() => [])
    ]);

    // ---------- KPI cards ----------
    document.getElementById('kpiRevenue').textContent = currency(summary.totalRevenueCollected);
    const avgRating = feedback.length
      ? (feedback.reduce((sum, f) => sum + (f.rating || 0), 0) / feedback.length).toFixed(1)
      : '—';
    document.getElementById('kpiRating').textContent = avgRating;
    const occupancy = summary.totalBeds > 0 ? Math.round((summary.occupiedBeds / summary.totalBeds) * 100) : 0;
    document.getElementById('kpiOccupancy').textContent = `${occupancy}%`;

    // ---------- Appointments trend (last 7 distinct dates present) ----------
    const dateCounts = {};
    appointments.forEach(a => {
      if (!a.appointmentDate) return;
      dateCounts[a.appointmentDate] = (dateCounts[a.appointmentDate] || 0) + 1;
    });
    const sortedDates = Object.keys(dateCounts).sort().slice(-7);
    const apptTrend = {
      labels: sortedDates.map(d => new Date(d + 'T00:00:00').toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })),
      data: sortedDates.map(d => dateCounts[d])
    };

    // ---------- Invoice status breakdown ----------
    const statusTotals = {};
    billings.forEach(b => {
      statusTotals[b.status] = (statusTotals[b.status] || 0) + (Number(b.amount) || 0);
    });
    const invoiceBreakdown = {
      labels: Object.keys(statusTotals),
      data: Object.values(statusTotals),
      colors: Object.keys(statusTotals).map(s => s === 'Paid' ? brand.teal : s === 'Unpaid' ? brand.coral : brand.gold)
    };

    // ---------- Blood group stock ----------
    const groups = ['A+', 'A-', 'B+', 'B-', 'O+', 'O-', 'AB+', 'AB-'];
    const groupTotals = Object.fromEntries(groups.map(g => [g, 0]));
    bloodBank.forEach(e => { if (groupTotals[e.bloodGroup] !== undefined) groupTotals[e.bloodGroup] += e.unitsAvailable || 0; });
    const bloodGroupCount = { labels: groups, data: groups.map(g => groupTotals[g]) };

    // ---------- Doctors by specialization ----------
    const specCounts = {};
    doctors.forEach(d => {
      const spec = d.specialization || 'Other';
      specCounts[spec] = (specCounts[spec] || 0) + 1;
    });
    const specializationCount = { labels: Object.keys(specCounts), data: Object.values(specCounts) };

    renderCharts(apptTrend, invoiceBreakdown, bloodGroupCount, specializationCount);

    latestExportLines = [
      'LIFETAG — Reports Summary',
      `Generated: ${new Date().toLocaleString('en-IN')}`,
      '',
      `Total revenue collected: ${currency(summary.totalRevenueCollected)}`,
      `Total pending billing: ${currency(summary.totalPendingBilling)}`,
      `Average patient rating: ${avgRating} / 5`,
      `Overall bed occupancy: ${occupancy}% (${summary.occupiedBeds}/${summary.totalBeds} beds)`,
      `Total patients: ${summary.totalPatients} · Total doctors: ${summary.totalDoctors} · Total appointments: ${summary.totalAppointments}`,
      '',
      'Appointments by date: ' + sortedDates.map(d => `${d}=${dateCounts[d]}`).join(', '),
      'Invoice breakdown: ' + invoiceBreakdown.labels.map((l, i) => `${l}=${currency(invoiceBreakdown.data[i])}`).join(', '),
      'Doctors by specialization: ' + specializationCount.labels.map((l, i) => `${l}=${specializationCount.data[i]}`).join(', ')
    ];
  } catch (err) {
    const hint = err.message && err.message.includes('401')
      ? ' You may need to log in again — go to the login page and sign back in.'
      : '';
    showError(`Couldn't load reports: ${err.message}.${hint}`);
  }
}

function renderCharts(apptTrend, invoiceBreakdown, bloodGroupCount, specializationCount) {
  if (!chartJsAvailable) return; // already warned above; KPI cards + export still work fine without it

  new Chart(document.getElementById('apptTrendChart'), {
    type: 'line',
    data: {
      labels: apptTrend.labels,
      datasets: [{
        label: 'Appointments',
        data: apptTrend.data,
        borderColor: brand.teal,
        backgroundColor: brand.tealLight,
        fill: true,
        tension: 0.35,
        pointRadius: 4,
        pointBackgroundColor: brand.teal
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        y: { beginAtZero: true, grid: { color: brand.paper } },
        x: { grid: { display: false } }
      }
    }
  });

  new Chart(document.getElementById('revenueChart'), {
    type: 'doughnut',
    data: {
      labels: invoiceBreakdown.labels,
      datasets: [{ data: invoiceBreakdown.data, backgroundColor: invoiceBreakdown.colors, borderWidth: 0 }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      cutout: '65%',
      plugins: { legend: { position: 'bottom', labels: { boxWidth: 10, padding: 16 } } }
    }
  });

  new Chart(document.getElementById('bloodGroupChart'), {
    type: 'bar',
    data: {
      labels: bloodGroupCount.labels,
      datasets: [{ label: 'Units in stock', data: bloodGroupCount.data, backgroundColor: brand.navy, borderRadius: 5, maxBarThickness: 34 }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        y: { beginAtZero: true, grid: { color: brand.paper } },
        x: { grid: { display: false } }
      }
    }
  });

  new Chart(document.getElementById('specializationChart'), {
    type: 'bar',
    data: {
      labels: specializationCount.labels,
      datasets: [{ label: 'Doctors', data: specializationCount.data, backgroundColor: brand.coral, borderRadius: 5, maxBarThickness: 22 }]
    },
    options: {
      indexAxis: 'y',
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        x: { beginAtZero: true, ticks: { stepSize: 1 }, grid: { color: brand.paper } },
        y: { grid: { display: false } }
      }
    }
  });
}

// ---------- Export summary (downloads a plain text file) ----------
document.getElementById('exportBtn').addEventListener('click', () => {
  const blob = new Blob([latestExportLines.join('\n')], { type: 'text/plain' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'lifetag-report-summary.txt';
  a.click();
  URL.revokeObjectURL(url);
});

loadReports();
