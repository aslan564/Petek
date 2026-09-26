/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

// Is the given text, or an element matching the given CSS selector, visible right now?
// Polled inside the page by `page.waitForFunction`, so a change is seen within one polling interval instead of
// Playwright's locator retry schedule (which backs off to 500 ms) — this is what makes t1 of a latency precise.
//
// Text: case-insensitive, whitespace-normalized substring of the rendered text (`innerText` skips `display:none`
// and `visibility:hidden` content), open shadow roots included like `getByText` does. Selector: any match in the
// document or in open shadow roots with a non-empty box and visible CSS visibility.
({ text, selector }) => {
  const collapse = (value) => (value || '').replace(/\s+/g, ' ').trim().toLowerCase();

  const shadowRoots = () => {
    const roots = [];
    const visit = (root) => {
      const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        if (node.shadowRoot) {
          roots.push(node.shadowRoot);
          visit(node.shadowRoot);
        }
      }
    };
    visit(document);
    return roots;
  };

  // `document.body.innerText` never includes shadow trees, so their rendered text is collected separately.
  const hasBox = (el) => typeof el.checkVisibility !== 'function' || el.checkVisibility();
  const renderedText = (nodes) => {
    const parts = [];
    for (const node of nodes) {
      if (node.nodeType === Node.TEXT_NODE) {
        parts.push(node.textContent);
      } else if (node.nodeType === Node.ELEMENT_NODE) {
        if (node.tagName === 'SLOT') {
          // Assigned light-DOM nodes are part of the body's text already; only fallback content is new.
          if (node.assignedNodes().length === 0) parts.push(renderedText(node.childNodes));
        } else if (hasBox(node)) {
          parts.push(node.innerText ?? node.textContent ?? '');
        } else if (getComputedStyle(node).display === 'contents') {
          parts.push(renderedText(node.childNodes));
        }
      }
    }
    return parts.join('\n');
  };
  const shadowText = () =>
    shadowRoots()
      .filter((root) => hasBox(root.host) || getComputedStyle(root.host).display === 'contents')
      .map((root) => renderedText(root.childNodes))
      .join('\n');

  if (text !== null && text !== undefined) {
    const wanted = collapse(text);
    if (!document.body) return false;
    return collapse(document.body.innerText).includes(wanted) || collapse(shadowText()).includes(wanted);
  }

  const isVisible = (el) => {
    if (typeof el.checkVisibility === 'function' &&
        !el.checkVisibility({ checkVisibilityCSS: true, visibilityProperty: true })) {
      return false;
    }
    const rect = el.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  };
  const matches = (root) => Array.from(root.querySelectorAll(selector)).some(isVisible);
  return matches(document) || shadowRoots().some(matches);
}
