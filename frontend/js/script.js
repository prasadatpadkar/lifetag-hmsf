// ============================================
// ROLE SWITCH
// ============================================
const roleButtons = document.querySelectorAll('.role-btn');
const roleHint = document.getElementById('roleHint');
const usernameInput = document.getElementById('username');

const roleCopy = {
  admin:     { hint: 'Enter your admin credentials to continue.',      placeholder: 'e.g. admin' },
  doctor:    { hint: 'Enter your doctor username and password.',       placeholder: 'e.g. doctor' },
  reception: { hint: 'Enter your reception desk credentials.',         placeholder: 'e.g. reception' },
  patient:   { hint: 'Enter your patient username and password.',      placeholder: 'e.g. patient' }
};

let currentRole = 'admin';

const patientSignupNote = document.getElementById('patientSignupNote');

roleButtons.forEach(btn => {
  btn.addEventListener('click', () => {
    roleButtons.forEach(b => b.classList.remove('is-active'));
    btn.classList.add('is-active');

    currentRole = btn.dataset.role;
    const copy = roleCopy[currentRole];
    roleHint.textContent = copy.hint;
    usernameInput.placeholder = copy.placeholder;

    // Self-signup only makes sense for patients — Doctor/Reception/Admin
    // accounts are created by an administrator (see staff-management.html /
    // doctor-management.html), who hands the new starter a temporary password.
    if (patientSignupNote) patientSignupNote.style.display = currentRole === 'patient' ? 'block' : 'none';
  });
});

// ============================================
// FORM VALIDATION + LOGIN (calls the Spring Boot backend via LifetagAPI,
// see js/api-config.js)
// ============================================
const form = document.getElementById('loginForm');
const passwordInput = document.getElementById('password');
const usernameError = document.getElementById('usernameError');
const passwordError = document.getElementById('passwordError');
const formNote = document.getElementById('formNote');
const submitBtn = form.querySelector('button[type="submit"]');
const rememberInput = document.getElementById('remember');

// "Remember this device": pre-fill the last username and re-tick the box on return visits.
try {
  const savedUser = localStorage.getItem('lifetag_remembered_username');
  if (savedUser && rememberInput) {
    usernameInput.value = savedUser;
    rememberInput.checked = true;
  }
} catch (e) { /* storage unavailable */ }

const roleDisplayNames = { admin: 'Admin', doctor: 'Doctor', reception: 'Reception', patient: 'Patient' };

form.addEventListener('submit', async function (e) {
  e.preventDefault();

  let isValid = true;
  usernameError.textContent = '';
  passwordError.textContent = '';
  usernameInput.classList.remove('is-invalid');
  passwordInput.classList.remove('is-invalid');
  formNote.textContent = '';
  formNote.className = 'form-note';

  if (usernameInput.value.trim() === '') {
    usernameError.textContent = 'Username or ID is required.';
    usernameInput.classList.add('is-invalid');
    isValid = false;
  }

  if (passwordInput.value.trim() === '') {
    passwordError.textContent = 'Password is required.';
    passwordInput.classList.add('is-invalid');
    isValid = false;
  } else if (passwordInput.value.length < 6) {
    passwordError.textContent = 'Password must be at least 6 characters.';
    passwordInput.classList.add('is-invalid');
    isValid = false;
  }

  if (!isValid) return;

  submitBtn.disabled = true;
  const originalBtnText = submitBtn.textContent;
  submitBtn.textContent = 'Signing in...';

  try {
    const data = await LifetagAPI.login(usernameInput.value.trim(), passwordInput.value, rememberInput && rememberInput.checked);
    // data: { token, username, role, fullName } — role comes back as "ADMIN" / "DOCTOR" / etc.
    const role = (data.role || '').toLowerCase();

    // The account's REAL role (from the server) must match the tab the person
    // selected — otherwise this login silently succeeds into the wrong portal
    // no matter which tab was clicked, which is confusing and lets, say, an
    // Admin account end up inside the Doctor dashboard just because Admin was
    // typed while the Doctor tab happened to be selected.
    if (role !== currentRole) {
      formNote.textContent = `That account is a ${roleDisplayNames[role] || data.role} account. `
        + `Switch to the "${roleDisplayNames[role] || data.role}" tab above to sign in.`;
      formNote.classList.add('is-error');
      submitBtn.disabled = false;
      submitBtn.textContent = originalBtnText;
      return;
    }

    const remember = !!(rememberInput && rememberInput.checked);
    LifetagAPI.setSession(data, remember);
    try {
      if (remember) localStorage.setItem('lifetag_remembered_username', data.username);
      else localStorage.removeItem('lifetag_remembered_username');
    } catch (e) { /* storage unavailable */ }

    // Keep a small, human-friendly session record so the existing dashboard/activity-log
    // pages (which were built against localStorage mock data) keep working unchanged.
    localStorage.setItem('lifetag_current_user', JSON.stringify({
      name: data.fullName || data.username,
      role: roleDisplayNames[role] || data.role
    }));

    formNote.textContent = `Signed in as ${roleDisplayNames[role] || data.role}. Redirecting to dashboard...`;
    formNote.classList.add('is-success');

    setTimeout(() => {
      window.location.href = `pages/${role}-dashboard.html`;
    }, 700);
  } catch (err) {
    formNote.textContent = err.message || 'Login failed. Please check your credentials.';
    formNote.classList.add('is-error');
    submitBtn.disabled = false;
    submitBtn.textContent = originalBtnText;
  }
});
