// ============================================
// CHANGE PASSWORD — calls POST /api/auth/change-password, which requires
// the account's CURRENT (or temporary) password before allowing a new
// one to be set. This replaces the old email-token "forgot password"
// flow: a username/email alone was never enough proof of identity to
// hand over a password reset, and this build has no real mail server to
// deliver a reset link safely anyway. Someone who's genuinely forgotten
// their password entirely needs an administrator (or Reception, for
// patients) to issue a fresh temporary one instead.
// ============================================

const form = document.getElementById('changePasswordForm');
const usernameInput = document.getElementById('username');
const oldPasswordInput = document.getElementById('oldPassword');
const newPasswordInput = document.getElementById('newPassword');
const confirmPasswordInput = document.getElementById('confirmPassword');

const usernameError = document.getElementById('usernameError');
const oldPasswordError = document.getElementById('oldPasswordError');
const newPasswordError = document.getElementById('newPasswordError');
const confirmPasswordError = document.getElementById('confirmPasswordError');
const formNote = document.getElementById('formNote');

const submitBtn = form.querySelector('button[type="submit"]');

function clearErrors() {
  [usernameError, oldPasswordError, newPasswordError, confirmPasswordError].forEach(el => { el.textContent = ''; });
  [usernameInput, oldPasswordInput, newPasswordInput, confirmPasswordInput].forEach(el => el.classList.remove('is-invalid'));
  formNote.textContent = '';
  formNote.className = 'form-note';
}

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  clearErrors();

  let isValid = true;

  if (!usernameInput.value.trim()) {
    usernameError.textContent = 'Enter your username or email.';
    usernameInput.classList.add('is-invalid');
    isValid = false;
  }

  if (!oldPasswordInput.value) {
    oldPasswordError.textContent = 'Enter your current password.';
    oldPasswordInput.classList.add('is-invalid');
    isValid = false;
  }

  if (!newPasswordInput.value || newPasswordInput.value.length < 8) {
    newPasswordError.textContent = 'New password must be at least 8 characters.';
    newPasswordInput.classList.add('is-invalid');
    isValid = false;
  }

  if (confirmPasswordInput.value !== newPasswordInput.value) {
    confirmPasswordError.textContent = "Passwords don't match.";
    confirmPasswordInput.classList.add('is-invalid');
    isValid = false;
  }

  if (!isValid) return;

  submitBtn.disabled = true;
  const originalText = submitBtn.textContent;
  submitBtn.textContent = 'Updating\u2026';

  try {
    const response = await LifetagAPI.changePassword(
      usernameInput.value.trim(),
      oldPasswordInput.value,
      newPasswordInput.value
    );

    formNote.textContent = response.message || 'Password updated! Redirecting you to sign in\u2026';
    formNote.classList.add('is-success');
    form.reset();

    setTimeout(() => {
      window.location.href = 'index.html';
    }, 1400);
  } catch (err) {
    formNote.textContent = err.message || 'Something went wrong. Please try again.';
    formNote.classList.add('is-error');
    submitBtn.disabled = false;
    submitBtn.textContent = originalText;
  }
});
