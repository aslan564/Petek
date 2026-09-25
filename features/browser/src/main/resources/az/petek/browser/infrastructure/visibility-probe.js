// Is the given text, or an element matching the given CSS selector, visible right now?
// Polled inside the page by `page.waitForFunction`, so a change is seen within one polling interval instead of
// Playwright's locator retry schedule (which backs off to 500 ms) — this is what makes t1 of a latency precise.
//
// Text: case-insensitive, whitespace-normalized substring of the rendered text (`innerText` skips `display:none`
// and `visibility:hidden` content). Selector: any match in the document or in open shadow roots with a non-empty
// box and visible CSS visibility.
({ text, selector }) => {
  const collapse = (value) => (value || '').replace(/\s+/g, ' ').trim().toLowerCase();
  if (text !== null && text !== undefined) {
    return !!document.body && collapse(document.body.innerText).includes(collapse(text));
  }
  const isVisible = (el) => {
    if (typeof el.checkVisibility === 'function' &&
        !el.checkVisibility({ checkVisibilityCSS: true, visibilityProperty: true })) {
      return false;
    }
    const rect = el.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  };
  const search = (root) => {
    for (const el of root.querySelectorAll(selector)) {
      if (isVisible(el)) return true;
    }
    for (const host of root.querySelectorAll('*')) {
      if (host.shadowRoot && search(host.shadowRoot)) return true;
    }
    return false;
  };
  return search(document);
}
