// Current non-empty values of the page's secret fields, so text taken from the page (e.g. an ARIA snapshot)
// can be redacted before it leaves the browser adapter.
() =>
  Array.from(document.querySelectorAll('input'))
    .filter(
      (input) =>
        (input.getAttribute('type') || '').toLowerCase() === 'password' ||
        /password/i.test(input.getAttribute('autocomplete') || ''),
    )
    .map((input) => input.value)
    .filter((value) => value && value.length > 0)
