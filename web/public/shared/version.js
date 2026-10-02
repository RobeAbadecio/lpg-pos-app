// Release number shown in the POS and the admin dashboard.
// On every update: bump it, set the date, and add an entry to CHANGELOG.md.
window.LPG_VERSION = '3.1.0';
window.LPG_RELEASED = '2026-10-02';

(() => {
  const date = new Date(`${window.LPG_RELEASED}T00:00:00`)
    .toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
  document.querySelectorAll('[data-version]').forEach((el) => {
    el.textContent = el.dataset.version === 'long'
      ? `Version ${window.LPG_VERSION} · updated ${date}`
      : `v${window.LPG_VERSION}`;
  });
})();
