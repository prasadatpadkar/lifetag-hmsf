/**
 * LifeTag HMS - Backend API client
 * -----------------------------------------------------------------------
 * Thin fetch() wrapper for the Spring Boot backend (see /backend folder).
 * Include this file BEFORE any page-specific script that needs live data:
 *
 *   <script src="../js/api-config.js"></script>
 *
 * Start the backend first:  cd backend && mvn spring-boot:run
 * It listens on http://localhost:8080 by default.
 *
 * Logins: with the default local H2 profile, demo accounts are seeded on first run
 * (see backend/CREDENTIALS.md). In a real deployment there are no built-in logins - the
 * first admin is created from the BOOTSTRAP_ADMIN_* environment variables.
 */

// Resolves automatically so the same file works in both setups:
//  - Local dev: open the frontend on its own dev port (Live Server/5500, http-server/8081,
//    or file://) while the backend runs separately with `mvn spring-boot:run` on :8080.
//  - Docker/production: the frontend is served by nginx, which reverse-proxies /api/* to the
//    backend container on the SAME origin (see frontend/nginx.conf) — no CORS needed at all.
const API_BASE_URL = (() => {
  const { hostname, port, protocol } = window.location;
  const isDevServer = protocol === "file:" || (["localhost", "127.0.0.1"].includes(hostname) && port && port !== "80");
  return isDevServer ? "http://localhost:8080/api" : "/api";
})();

/**
 * Escapes a value for safe insertion into HTML text OR a quoted attribute value.
 * EVERY value that comes from the API or from user input and is interpolated into an
 * innerHTML / insertAdjacentHTML template string MUST go through this - names, reasons,
 * remarks, addresses etc. are attacker-controlled (any patient can self-register) and
 * the login token lives in localStorage, so unescaped values are a stored-XSS hole.
 * Never put user-controlled text inside an inline on*="" handler; use data-* attributes
 * and addEventListener instead.
 */
function escapeHtml(value) {
  return String(value === null || value === undefined ? "" : value).replace(/[&<>"']/g, (c) => (
    { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]
  ));
}


// ---------------------------------------------------------------------------
// Page guard: every screen under /pages/ requires a signed-in session of the
// right role. Without it, the page is never shown and the visitor is sent to
// the login screen. (This is a usability guard — the real protection is the
// backend, which rejects any API call without a valid token for that role.)
// ---------------------------------------------------------------------------
const LifetagGuard = (() => {
  const ADMIN_PAGES = [
    "admin-dashboard", "appointment-management", "billing-payment", "blood-bank-management",
    "doctor-management", "feedback", "insurance-management", "patient-management",
    "reports-analytics", "room-bed-management", "schemes-management", "staff-activity",
    "staff-management", "audit-log", "menu-management", "admin-patient-record", "admission-management",
  ];

  function requiredRole(file) {
    const name = file.replace(/\.html$/, "");
    if (ADMIN_PAGES.includes(name)) return "ADMIN";
    const prefix = name.split("-")[0].toUpperCase();
    return ["RECEPTION", "DOCTOR", "PATIENT"].includes(prefix) ? prefix : null;
  }

  function tokenExpired(token) {
    try {
      const payload = JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
      return payload.exp && payload.exp * 1000 < Date.now();
    } catch (e) {
      return true; // not a readable token -> treat as invalid
    }
  }

  function toLogin() {
    ["lifetag_token", "lifetag_username", "lifetag_role", "lifetag_fullName", "lifetag_current_user"]
      .forEach((k) => localStorage.removeItem(k));
    window.location.replace(window.location.pathname.replace(/\/pages\/[^/]*$/, "/index.html"));
  }

  function check() {
    if (!window.location.pathname.includes("/pages/")) return; // login/signup pages are public
    const file = window.location.pathname.split("/").pop();
    const token = localStorage.getItem("lifetag_token");
    const role = (localStorage.getItem("lifetag_role") || "").toUpperCase();
    const needed = requiredRole(file);

    if (!token || tokenExpired(token)) { document.documentElement.style.display = "none"; return toLogin(); }
    if (needed && role !== needed) {
      // Signed in, but on another role's page: send them to their own dashboard.
      document.documentElement.style.display = "none";
      return window.location.replace(`${role.toLowerCase()}-dashboard.html`);
    }
  }

  return { check, toLogin };
})();
LifetagGuard.check();
// Re-check when a page is restored from the browser's back/forward cache, so pressing
// Back after logging out can't show a protected page from memory.
window.addEventListener("pageshow", (e) => { if (e.persisted) LifetagGuard.check(); });

const LifetagAPI = {

  // ---- token storage ----
  getToken() {
    return localStorage.getItem("lifetag_token");
  },
  setSession({ token, username, role, fullName }) {
    localStorage.setItem("lifetag_token", token);
    localStorage.setItem("lifetag_username", username);
    localStorage.setItem("lifetag_role", role);
    localStorage.setItem("lifetag_fullName", fullName || "");
  },
  clearSession() {
    localStorage.removeItem("lifetag_token");
    localStorage.removeItem("lifetag_username");
    localStorage.removeItem("lifetag_role");
    localStorage.removeItem("lifetag_fullName");
  },
  isLoggedIn() {
    return !!this.getToken();
  },

  // ---- low-level request helper ----
  async request(path, { method = "GET", body, auth = true } = {}) {
    const headers = { "Content-Type": "application/json" };
    if (auth && this.getToken()) {
      headers["Authorization"] = `Bearer ${this.getToken()}`;
    }

    const response = await fetch(`${API_BASE_URL}${path}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });

    if (response.status === 204) return null;

    let data = null;
    try {
      data = await response.json();
    } catch (e) {
      // no JSON body
    }

    // An expired/invalid session on an authenticated call: back to login.
    if (response.status === 401 && auth && this.getToken()) {
      LifetagGuard.toLogin();
    }

    if (!response.ok) {
      const message = (data && data.message) || `Request failed (${response.status})`;
      throw new Error(message);
    }
    return data;
  },

  /** Downloads a binary file (e.g. a PDF) from an authenticated endpoint and hands it
   *  to the browser as a save-as download. A plain <a href> can't be used because the
   *  JWT travels in the Authorization header, not a cookie. */
  async download(path, filename) {
    const headers = {};
    if (this.getToken()) headers["Authorization"] = `Bearer ${this.getToken()}`;
    const response = await fetch(`${API_BASE_URL}${path}`, { headers });
    if (!response.ok) {
      let message = `Download failed (${response.status})`;
      try {
        const data = await response.json();
        if (data && data.message) message = data.message;
      } catch (e) {
        // no JSON body
      }
      throw new Error(message);
    }
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  },

  // ---- auth ----
  login(username, password) {
    return this.request("/auth/login", { method: "POST", body: { username, password }, auth: false });
  },
  register(payload) {
    return this.request("/auth/register", { method: "POST", body: payload, auth: false });
  },
  me() {
    return this.request("/auth/me");
  },
  /** Self-service change: requires the account's current/temporary password —
   *  see AuthController#changePassword for why a username alone isn't enough. */
  changePassword(username, oldPassword, newPassword) {
    return this.request("/auth/change-password", { method: "POST", body: { username, oldPassword, newPassword }, auth: false });
  },
  /** Admin/Reception-issued reset for someone who's forgotten their password entirely. */
  resetDoctorPassword(id) { return this.request(`/doctors/${id}/reset-password`, { method: "POST" }); },
  resetPatientPassword(id) { return this.request(`/patients/${id}/reset-password`, { method: "POST" }); },
  resetStaffPassword(id) { return this.request(`/staff/account/${id}/reset-password`, { method: "POST" }); },

  // ---- patients ----
  // ---- menu + audit log (admin) ----
  getMyMenu() { return this.request("/menu"); },
  getAdminMenu() { return this.request("/admin/menu"); },
  createMenuItem(data) { return this.request("/admin/menu", { method: "POST", body: data }); },
  updateMenuItem(id, data) { return this.request(`/admin/menu/${id}`, { method: "PUT", body: data }); },
  deleteMenuItem(id) { return this.request(`/admin/menu/${id}`, { method: "DELETE" }); },
  getAuditLogs(params = {}) {
    const q = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => { if (v !== "" && v !== null && v !== undefined) q.set(k, v); });
    return this.request(`/audit-logs?${q.toString()}`);
  },

  getPatients() { return this.request("/patients"); },
  getPatient(id) { return this.request(`/patients/${id}`); },
  /** Resolves the Patient profile linked to the signed-in account (role PATIENT). */
  getMyPatientProfile() { return this.request("/patients/me"); },
  createPatient(data) { return this.request("/patients", { method: "POST", body: data }); },
  updatePatient(id, data) { return this.request(`/patients/${id}`, { method: "PUT", body: data }); },
  deletePatient(id) { return this.request(`/patients/${id}`, { method: "DELETE" }); },

  // ---- doctors ----
  getDoctors() { return this.request("/doctors"); },
  getDoctor(id) { return this.request(`/doctors/${id}`); },
  /** Resolves the Doctor profile linked to the signed-in account (role DOCTOR). */
  getMyDoctorProfile() { return this.request("/doctors/me"); },
  createDoctor(data) { return this.request("/doctors", { method: "POST", body: data }); },
  updateDoctor(id, data) { return this.request(`/doctors/${id}`, { method: "PUT", body: data }); },
  deleteDoctor(id) { return this.request(`/doctors/${id}`, { method: "DELETE" }); },

  // ---- appointments ----
  getAppointments() { return this.request("/appointments"); },
  getAppointmentsByPatient(patientId) { return this.request(`/appointments/patient/${patientId}`); },
  getAppointmentsByDoctor(doctorId) { return this.request(`/appointments/doctor/${doctorId}`); },
  createAppointment(data) { return this.request("/appointments", { method: "POST", body: data }); },
  updateAppointment(id, data) { return this.request(`/appointments/${id}`, { method: "PUT", body: data }); },
  deleteAppointment(id) { return this.request(`/appointments/${id}`, { method: "DELETE" }); },

  // ---- billing ----
  getBillings() { return this.request("/billing"); },
  getBillingsByPatient(patientId) { return this.request(`/billing/patient/${patientId}`); },
  createBilling(data) { return this.request("/billing", { method: "POST", body: data }); },
  updateBilling(id, data) { return this.request(`/billing/${id}`, { method: "PUT", body: data }); },
  deleteBilling(id) { return this.request(`/billing/${id}`, { method: "DELETE" }); },
  /** Itemised invoice: bill, line items, per-category spending breakdown, totals, payments, hospital details. */
  getInvoice(id) { return this.request(`/billing/${id}/invoice`); },
  getBillingCategories() { return this.request("/billing/categories"); },
  /** Un-invoiced charges (consultations, bed days, scans, procedures, medicines) from the clinical record. */
  getSuggestedCharges(patientId) { return this.request(`/billing/suggest/${patientId}`); },
  addPayment(id, data) { return this.request(`/billing/${id}/payments`, { method: "POST", body: data }); },
  getRevenueByCategory() { return this.request("/reports/revenue-by-category"); },

  // ---- clinical record: vitals, diagnostic reports, treatments, admissions ----
  getPatientRecord(patientId) { return this.request(`/clinical/patient/${patientId}/summary`); },
  addVitals(data) { return this.request("/clinical/vitals", { method: "POST", body: data }); },
  deleteVitals(id) { return this.request(`/clinical/vitals/${id}`, { method: "DELETE" }); },
  addReport(data) { return this.request("/clinical/reports", { method: "POST", body: data }); },
  deleteReport(id) { return this.request(`/clinical/reports/${id}`, { method: "DELETE" }); },
  addTreatment(data) { return this.request("/clinical/treatments", { method: "POST", body: data }); },
  updateTreatment(id, data) { return this.request(`/clinical/treatments/${id}`, { method: "PUT", body: data }); },
  deleteTreatment(id) { return this.request(`/clinical/treatments/${id}`, { method: "DELETE" }); },
  getAdmissions(status) { return this.request("/clinical/admissions" + (status ? `?status=${encodeURIComponent(status)}` : "")); },
  admitPatient(data) { return this.request("/clinical/admissions", { method: "POST", body: data }); },
  dischargePatient(id, data) { return this.request(`/clinical/admissions/${id}/discharge`, { method: "POST", body: data || {} }); },

  // ---- rooms & beds ----
  getRoomsBeds() { return this.request("/rooms-beds"); },
  createRoomBed(data) { return this.request("/rooms-beds", { method: "POST", body: data }); },
  updateRoomBed(id, data) { return this.request(`/rooms-beds/${id}`, { method: "PUT", body: data }); },
  assignRoomBed(id, patientId) { return this.request(`/rooms-beds/${id}/assign`, { method: "PATCH", body: { patientId } }); },
  deleteRoomBed(id) { return this.request(`/rooms-beds/${id}`, { method: "DELETE" }); },

  // ---- blood bank ----
  getBloodBank() { return this.request("/blood-bank"); },
  createBloodEntry(data) { return this.request("/blood-bank", { method: "POST", body: data }); },
  updateBloodEntry(id, data) { return this.request(`/blood-bank/${id}`, { method: "PUT", body: data }); },
  deleteBloodEntry(id) { return this.request(`/blood-bank/${id}`, { method: "DELETE" }); },

  // ---- blood requests ----
  getBloodRequests() { return this.request("/blood-requests"); },
  createBloodRequest(data) { return this.request("/blood-requests", { method: "POST", body: data }); },
  updateBloodRequest(id, data) { return this.request(`/blood-requests/${id}`, { method: "PUT", body: data }); },
  deleteBloodRequest(id) { return this.request(`/blood-requests/${id}`, { method: "DELETE" }); },

  // ---- insurance providers ----
  getInsuranceProviders() { return this.request("/insurance-providers"); },
  createInsuranceProvider(data) { return this.request("/insurance-providers", { method: "POST", body: data }); },
  updateInsuranceProvider(id, data) { return this.request(`/insurance-providers/${id}`, { method: "PUT", body: data }); },
  deleteInsuranceProvider(id) { return this.request(`/insurance-providers/${id}`, { method: "DELETE" }); },

  // ---- insurance ----
  getInsurance() { return this.request("/insurance"); },
  getInsuranceByPatient(patientId) { return this.request(`/insurance/patient/${patientId}`); },
  createInsurance(data) { return this.request("/insurance", { method: "POST", body: data }); },
  updateInsurance(id, data) { return this.request(`/insurance/${id}`, { method: "PUT", body: data }); },
  deleteInsurance(id) { return this.request(`/insurance/${id}`, { method: "DELETE" }); },

  // ---- insurance claims ----
  getInsuranceClaims() { return this.request("/insurance-claims"); },
  getInsuranceClaimsByPatient(patientId) { return this.request(`/insurance-claims/patient/${patientId}`); },
  createInsuranceClaim(data) { return this.request("/insurance-claims", { method: "POST", body: data }); },
  decideInsuranceClaim(id, data) { return this.request(`/insurance-claims/${id}`, { method: "PUT", body: data }); },
  downloadInsuranceClaimSummary(id) { return this.download(`/insurance-claims/${id}/summary.pdf`, `insurance-claim-${id}-summary.pdf`); },
  deleteInsuranceClaim(id) { return this.request(`/insurance-claims/${id}`, { method: "DELETE" }); },

  // ---- schemes ----
  getSchemes() { return this.request("/schemes"); },
  createScheme(data) { return this.request("/schemes", { method: "POST", body: data }); },
  updateScheme(id, data) { return this.request(`/schemes/${id}`, { method: "PUT", body: data }); },
  deleteScheme(id) { return this.request(`/schemes/${id}`, { method: "DELETE" }); },

  // ---- patient scheme enrollments ----
  getPatientSchemes() { return this.request("/patient-schemes"); },
  getPatientSchemesByPatient(patientId) { return this.request(`/patient-schemes/patient/${patientId}`); },
  enrollScheme(data) { return this.request("/patient-schemes", { method: "POST", body: data }); },
  updatePatientScheme(id, data) { return this.request(`/patient-schemes/${id}`, { method: "PUT", body: data }); },
  verifyPatientScheme(id, status) { return this.request(`/patient-schemes/${id}/verify`, { method: "PUT", body: { status } }); },
  deletePatientScheme(id) { return this.request(`/patient-schemes/${id}`, { method: "DELETE" }); },

  // ---- scheme claims ----
  getSchemeClaims() { return this.request("/scheme-claims"); },
  getSchemeClaimsByPatient(patientId) { return this.request(`/scheme-claims/patient/${patientId}`); },
  createSchemeClaim(data) { return this.request("/scheme-claims", { method: "POST", body: data }); },
  decideSchemeClaim(id, data) { return this.request(`/scheme-claims/${id}`, { method: "PUT", body: data }); },
  downloadSchemeSanctionLetter(id) { return this.download(`/scheme-claims/${id}/sanction-letter.pdf`, `scheme-claim-${id}-sanction-letter.pdf`); },
  deleteSchemeClaim(id) { return this.request(`/scheme-claims/${id}`, { method: "DELETE" }); },

  // ---- feedback ----
  getFeedback() { return this.request("/feedback"); },
  createFeedback(data) { return this.request("/feedback", { method: "POST", body: data }); },
  updateFeedback(id, data) { return this.request(`/feedback/${id}`, { method: "PUT", body: data }); },
  deleteFeedback(id) { return this.request(`/feedback/${id}`, { method: "DELETE" }); },

  // ---- staff activity ----
  getStaffActivity() { return this.request("/staff-activity"); },
  logStaffActivity(data) { return this.request("/staff-activity", { method: "POST", body: data }); },

  // ---- notifications ----
  getNotifications() { return this.request("/notifications"); },
  getUnreadNotificationCount() { return this.request("/notifications/unread-count"); },
  markNotificationRead(id) { return this.request(`/notifications/${id}/read`, { method: "PUT" }); },
  markAllNotificationsRead() { return this.request("/notifications/read-all", { method: "PUT" }); },
  deleteNotification(id) { return this.request(`/notifications/${id}`, { method: "DELETE" }); },
  clearAllNotifications() { return this.request("/notifications", { method: "DELETE" }); },

  // ---- staff accounts (admin-only: reception desk + other admins) ----
  getReceptionists() { return this.request("/staff/RECEPTION"); },
  createReceptionist(data) { return this.request("/staff/RECEPTION", { method: "POST", body: data }); },
  getAdmins() { return this.request("/staff/ADMIN"); },
  createAdmin(data) { return this.request("/staff/ADMIN", { method: "POST", body: data }); },
  updateStaffAccount(id, data) { return this.request(`/staff/account/${id}`, { method: "PUT", body: data }); },
  deactivateStaffAccount(id) { return this.request(`/staff/account/${id}`, { method: "DELETE" }); },

  // ---- reports ----
  getSummaryReport() { return this.request("/reports/summary"); },
};
