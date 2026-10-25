// Business registration and staff "join a store" forms. The server checks everything again.
(function () {
  const { request } = UI;
  const $ = (id) => document.getElementById(id);
  const forms = { business: $('business-form'), staff: $('staff-form') };

  function pick(which) {
    $('pick-business').setAttribute('aria-pressed', String(which === 'business'));
    $('pick-staff').setAttribute('aria-pressed', String(which === 'staff'));
    forms.business.hidden = which !== 'business';
    forms.staff.hidden = which !== 'staff';
    $('done').hidden = true;
  }
  $('pick-business').addEventListener('click', () => pick('business'));
  $('pick-staff').addEventListener('click', () => pick('staff'));
  if (location.hash === '#staff') pick('staff');

  function values(form) {
    const out = {};
    for (const el of form.elements) if (el.name) out[el.name] = el.value.trim();
    out.password = form.password.value; // passwords are sent as typed
    return out;
  }

  function firstProblem(form) {
    for (const el of form.elements) {
      if (!el.name || el.checkValidity()) continue;
      const label = el.closest('label').querySelector('span').firstChild.textContent.trim();
      el.focus();
      return el.validity.valueMissing ? label + ' is required' : el.validity.tooShort ? label + ' is too short' : 'Check ' + label.toLowerCase();
    }
    return null;
  }

  async function submit(form, path, success) {
    const err = form.querySelector('.acct-error');
    const btn = form.querySelector('button[type=submit]');
    const problem = firstProblem(form);
    err.hidden = !problem;
    err.textContent = problem || '';
    if (problem) return;
    btn.disabled = true;
    try {
      const res = await request('POST', path, values(form));
      form.hidden = true;
      $('done').textContent = success(res);
      $('done').hidden = false;
    } catch (e) {
      err.textContent = e.message;
      err.hidden = false;
    } finally {
      btn.disabled = false;
    }
  }

  forms.business.addEventListener('submit', (e) => {
    e.preventDefault();
    submit(forms.business, '/api/register', () => 'Thank you! Your business is registered and waiting for approval. Try signing in later with your username and password.');
  });
  forms.staff.addEventListener('submit', (e) => {
    e.preventDefault();
    forms.staff.code.value = forms.staff.code.value.toUpperCase();
    submit(forms.staff, '/api/join', (res) => 'Request sent to ' + res.store + '. You can sign in once the store owner approves your account.');
  });
})();
