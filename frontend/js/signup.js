// ============================================
// PATIENT SIGN UP — calls POST /api/auth/register, which the backend
// always creates as role PATIENT regardless of anything sent here (see
// AuthController#register). Doctor/Reception/Admin accounts can't be
// self-registered — they're created by an administrator.
// ============================================

const signupForm = document.getElementById('signupForm');
const fullNameInput = document.getElementById('fullName');
const emailInput = document.getElementById('email');
const usernameInput = document.getElementById('username');
const passwordInput = document.getElementById('password');

const fullNameError = document.getElementById('fullNameError');
const emailError = document.getElementById('emailError');
const usernameError = document.getElementById('usernameError');
const passwordError = document.getElementById('passwordError');
const formNote = document.getElementById('formNote');

const submitBtn = signupForm.querySelector('button[type="submit"]');

function clearErrors() {
  [fullNameError, emailError, usernameError, passwordError].forEach(el => { el.textContent = ''; });
  [fullNameInput, emailInput, usernameInput, passwordInput].forEach(el => el.classList.remove('is-invalid'));
  formNote.textContent = '';
  formNote.className = 'form-note';
}

signupForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  clearErrors();

  let isValid = true;

  if (!fullNameInput.value.trim()) {
    fullNameError.textContent = 'Full name is required.';
    fullNameInput.classList.add('is-invalid');
    isValid = false;
  }

  if (!emailInput.value.trim()) {
    emailError.textContent = 'Email is required.';
    emailInput.classList.add('is-invalid');
    isValid = false;
  }

  if (!usernameInput.value.trim()) {
    usernameError.textContent = 'Choose a username.';
    usernameInput.classList.add('is-invalid');
    isValid = false;
  }

  if (!passwordInput.value || passwordInput.value.length < 8) {
    passwordError.textContent = 'Password must be at least 8 characters.';
    passwordInput.classList.add('is-invalid');
    isValid = false;
  }

  if (!isValid) return;

  submitBtn.disabled = true;
  const originalText = submitBtn.textContent;
  submitBtn.textContent = 'Creating account\u2026';

  try {
    await LifetagAPI.register({
      fullName: fullNameInput.value.trim(),
      email: emailInput.value.trim(),
      username: usernameInput.value.trim(),
      password: passwordInput.value
    });

    formNote.textContent = 'Account created! Redirecting you to sign in\u2026';
    formNote.classList.add('is-success');
    signupForm.reset();

    setTimeout(() => {
      window.location.href = 'index.html';
    }, 1200);
  } catch (err) {
    formNote.textContent = err.message || 'Something went wrong. Please try again.';
    formNote.classList.add('is-error');
    submitBtn.disabled = false;
    submitBtn.textContent = originalText;
  }
});
