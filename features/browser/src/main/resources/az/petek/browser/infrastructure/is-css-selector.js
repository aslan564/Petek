// True when the selector is plain CSS the page itself can evaluate; Playwright-only selector syntax
// (`text=…`, `:has-text()`, `>>` chains, …) is not, and is left to Playwright locators.
(selector) => {
  try {
    document.createDocumentFragment().querySelector(selector);
    return true;
  } catch (error) {
    return false;
  }
}
