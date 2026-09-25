// Serializes the page like Playwright's `page.content()` (doctype + documentElement.outerHTML), but from a clone
// whose secret inputs carry no value attribute. Values typed by a user live in DOM properties, which outerHTML never
// serializes; server-rendered or script-set `value` attributes on password fields are blanked here.
() => {
  const clone = document.documentElement.cloneNode(true);
  for (const input of clone.querySelectorAll('input')) {
    const secret =
      (input.getAttribute('type') || '').toLowerCase() === 'password' ||
      /password/i.test(input.getAttribute('autocomplete') || '');
    if (secret && input.hasAttribute('value')) input.setAttribute('value', '');
  }
  const doctype = document.doctype ? new XMLSerializer().serializeToString(document.doctype) : '';
  return doctype + clone.outerHTML;
}
