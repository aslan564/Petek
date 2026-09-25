// Is this element a secret field (a password input, or any input the page marks as a password by autocomplete)?
// Text the adapter types into one is remembered and masked everywhere afterwards, even once the page shows it.
(element) =>
  element instanceof HTMLInputElement &&
  ((element.getAttribute('type') || '').toLowerCase() === 'password' ||
    /password/i.test(element.getAttribute('autocomplete') || ''))
