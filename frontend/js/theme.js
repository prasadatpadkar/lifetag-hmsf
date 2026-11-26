// ============================================
// THEME TOGGLE (dark / light mode)
// The actual theme is already applied before this file loads, via a tiny
// inline script in each page's <head> (reads localStorage synchronously
// so there's no flash of the wrong theme). This file just wires up the
// toggle button's click behavior and keeps the choice saved.
// ============================================

(function () {
  function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('lifetag_theme', theme);
  }

  function currentTheme() {
    return document.documentElement.getAttribute('data-theme') || 'light';
  }

  const btn = document.getElementById('themeToggleBtn');
  if (btn) {
    btn.addEventListener('click', () => {
      applyTheme(currentTheme() === 'dark' ? 'light' : 'dark');
    });
  }
})();
