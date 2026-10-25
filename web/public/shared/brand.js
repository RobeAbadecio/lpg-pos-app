// The store's look on every page: its accent color, logo and name. The color is remembered on this device
// so the page doesn't flash blue before /api/me answers.
(function () {
  const KEY = 'lpg-brand-color';
  const set = (color) => {
    if (color) document.documentElement.dataset.brand = color;
    else delete document.documentElement.dataset.brand;
  };
  try { set(localStorage.getItem(KEY) || ''); } catch (_) { /* storage blocked */ }

  // brand: me.brand from the server; name: the store's name. Images marked data-brand-logo show the logo
  // (or keep the LPG POS icon when there is none); elements marked data-store-name show the name.
  function apply(brand, name) {
    brand = brand || {};
    set(brand.color || '');
    try { localStorage.setItem(KEY, brand.color || ''); } catch (_) { /* storage blocked */ }
    for (const img of document.querySelectorAll('img[data-brand-logo]')) {
      if (!img.dataset.icon) img.dataset.icon = img.getAttribute('src');
      img.src = brand.logo || img.dataset.icon;
      img.classList.toggle('is-logo', !!brand.logo);
    }
    if (name) for (const el of document.querySelectorAll('[data-store-name]')) el.textContent = name;
  }

  window.Brand = { apply, colors: { '': '#2a78d6', green: '#1d8048', teal: '#0b7a7a', purple: '#7445c9', orange: '#b9530c', rose: '#bf2a62' },
    names: { '': 'Blue', green: 'Green', teal: 'Teal', purple: 'Purple', orange: 'Orange', rose: 'Rose' } };
})();
