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

// Numbers the visible interactive elements of the page for the agent (see SnapshotParser for the result shape).
// Refs from earlier snapshots are removed first, so `[data-petek-ref="N"]` always means "N in the latest snapshot".
// Secret fields never leave the page: a password value is reported as "******".
({ maxElements, maxNameChars, maxValueChars, maxTextChars }) => {
  const REF = 'data-petek-ref';
  const INTERACTIVE = [
    'a[href]',
    'button',
    'input:not([type="hidden" i])',
    'select',
    'textarea',
    '[role~="button" i]',
    '[role~="link" i]',
    '[role~="checkbox" i]',
    '[role~="radio" i]',
    '[role~="tab" i]',
    '[role~="menuitem" i]',
    '[role~="option" i]',
    '[role~="combobox" i]',
    '[role~="switch" i]',
    '[contenteditable]:not([contenteditable="false" i])',
    '[onclick]',
  ].join(',');
  const FORM_FIELDS = new Set(['INPUT', 'SELECT', 'TEXTAREA']);
  const BUTTON_INPUTS = new Set(['submit', 'button', 'reset', 'image']);

  const collapse = (text) => (text == null ? '' : String(text)).replace(/\s+/g, ' ').trim();
  const clip = (text, max) => (text.length > max ? text.slice(0, max - 1) + '…' : text);

  const candidates = [];
  const shadowRoots = [];
  const walk = (root) => {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
    for (let node = walker.currentNode; node; node = walker.nextNode()) {
      if (node.nodeType !== Node.ELEMENT_NODE) continue;
      if (node.hasAttribute(REF)) node.removeAttribute(REF);
      if (node.matches(INTERACTIVE)) candidates.push(node);
      if (node.shadowRoot) {
        shadowRoots.push(node.shadowRoot);
        walk(node.shadowRoot);
      }
    }
  };
  if (document.documentElement) walk(document.documentElement);

  const isVisible = (el) => {
    if (typeof el.checkVisibility === 'function' &&
        !el.checkVisibility({ checkVisibilityCSS: true, visibilityProperty: true })) {
      return false;
    }
    const rect = el.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  };

  const isSecret = (el) =>
    el.tagName === 'INPUT' &&
    ((el.getAttribute('type') || '').toLowerCase() === 'password' ||
      /password/i.test(el.getAttribute('autocomplete') || ''));

  const textOf = (el) => collapse(el.innerText !== undefined ? el.innerText : el.textContent);

  const labelledBy = (el) => {
    const ids = (el.getAttribute('aria-labelledby') || '').split(/\s+/).filter(Boolean);
    const root = el.getRootNode();
    return ids
      .map((id) => (root.getElementById ? root.getElementById(id) : null) || document.getElementById(id))
      .filter(Boolean)
      .map(textOf)
      .join(' ');
  };

  const nameOf = (el) => {
    const sources = [
      () => el.getAttribute('aria-label'),
      () => labelledBy(el),
      () => (el.labels ? Array.from(el.labels).map(textOf).join(' ') : ''),
      () => (el.tagName === 'INPUT' && BUTTON_INPUTS.has(el.type) ? el.value : ''),
      () => el.getAttribute('placeholder'),
      () => el.getAttribute('title'),
      () => el.getAttribute('alt') || (el.querySelector('img[alt]') || { getAttribute: () => '' }).getAttribute('alt'),
      () => (FORM_FIELDS.has(el.tagName) ? '' : textOf(el)),
    ];
    for (const source of sources) {
      const name = collapse(source());
      if (name) return clip(name, maxNameChars);
    }
    return '';
  };

  const roleOf = (el) => {
    const explicit = (el.getAttribute('role') || '').trim().split(/\s+/)[0];
    if (explicit) return explicit.toLowerCase();
    switch (el.tagName) {
      case 'A': return 'link';
      case 'BUTTON': return 'button';
      case 'TEXTAREA': return 'textbox';
      case 'SELECT': return el.multiple || el.size > 1 ? 'listbox' : 'combobox';
      case 'INPUT':
        switch ((el.type || 'text').toLowerCase()) {
          case 'checkbox': return 'checkbox';
          case 'radio': return 'radio';
          case 'submit': case 'button': case 'reset': case 'image': case 'file': return 'button';
          case 'range': return 'slider';
          case 'number': return 'spinbutton';
          case 'search': return 'searchbox';
          default: return 'textbox';
        }
      default:
        return el.isContentEditable ? 'textbox' : 'clickable';
    }
  };

  const valueOf = (el) => {
    if (el.tagName === 'INPUT') {
      if (isSecret(el)) return el.value ? '******' : '';
      const type = (el.type || 'text').toLowerCase();
      if (type === 'checkbox' || type === 'radio') return el.checked ? 'checked' : 'unchecked';
      if (BUTTON_INPUTS.has(type) || type === 'file') return null;
      return clip(el.value, maxValueChars);
    }
    if (el.tagName === 'TEXTAREA') return clip(el.value, maxValueChars);
    if (el.tagName === 'SELECT') {
      return clip(Array.from(el.selectedOptions).map((o) => collapse(o.label || o.text)).join(', '), maxValueChars);
    }
    const checked = el.getAttribute('aria-checked');
    if (checked !== null) return checked === 'true' ? 'checked' : 'unchecked';
    return null;
  };

  const isEnabled = (el) => !(el.matches(':disabled') || el.getAttribute('aria-disabled') === 'true');

  const elements = [];
  for (const el of candidates) {
    if (elements.length >= maxElements) break;
    if (!isVisible(el)) continue;
    const ref = elements.length + 1;
    el.setAttribute(REF, String(ref));
    elements.push({
      ref,
      role: roleOf(el),
      name: nameOf(el),
      tag: el.tagName.toLowerCase(),
      testId: el.getAttribute('data-testid'),
      value: valueOf(el),
      enabled: isEnabled(el),
    });
  }

  // `document.body.innerText` never includes shadow trees; their rendered text follows the body's.
  const hasBox = (el) => typeof el.checkVisibility !== 'function' || el.checkVisibility();
  const renderedText = (nodes) => {
    const parts = [];
    for (const node of nodes) {
      if (node.nodeType === Node.TEXT_NODE) {
        parts.push(node.textContent);
      } else if (node.nodeType === Node.ELEMENT_NODE) {
        if (node.tagName === 'SLOT') {
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
  const shadowText = shadowRoots
    .filter((root) => hasBox(root.host) || getComputedStyle(root.host).display === 'contents')
    .map((root) => renderedText(root.childNodes));

  const visibleText = [document.body ? document.body.innerText : '', ...shadowText]
    .join('\n')
    .split('\n')
    .map(collapse)
    .filter((line) => line.length > 0)
    .join('\n')
    .slice(0, maxTextChars);

  return { url: location.href, title: document.title, elements, visibleText };
}
