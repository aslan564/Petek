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

// What a look reads right after its final frame (PlaywrightBrowserSession.look): where the page landed, the status of
// its own answer, its height, the browser, the loaded web fonts, the areas not to compare and the elements a
// mask could name. Boxes are in page coordinates (CSS px; the page is at its top) and count only when visible and
// within the captured height:
// - PROFILE / STEP: the owner's mask selectors, the first `maxPerSelector` matches each; one the page refuses is listed;
// - MARKUP: `[data-petek-mask]`;
// - EMBED: video, object, embed, and frames of another origin (what a third party shows is not what the site did);
// - TIME_TEXT: `<time>`, and the dates and times written in text (only the matched words, never the whole element);
// - RUN_TEXT: the run's own texts and marks written in text, and fields whose value holds one. An area names the
//   text's kind, never the text.
// Open shadow roots are read like the document. Reads only: nothing is added to the page.
({ captureHeight, selectors, runTexts, runTag, maxAreas, maxPerSelector, maxAnchors, maxFonts }) => {
  const roots = [document];
  for (let i = 0; i < roots.length && roots.length < 200; i += 1) {
    for (const el of roots[i].querySelectorAll('*')) if (el.shadowRoot) roots.push(el.shadowRoot);
  }
  const all = (css) => roots.flatMap((root) => Array.from(root.querySelectorAll(css)));

  const visibility = new Map();
  const isVisible = (el) => {
    if (!visibility.has(el)) {
      visibility.set(el, typeof el.checkVisibility !== 'function' ||
        el.checkVisibility({ checkVisibilityCSS: true, visibilityProperty: true }));
    }
    return visibility.get(el);
  };
  const inCapture = (box) => box.width > 0 && box.height > 0 && box.top + scrollY < captureHeight && box.bottom + scrollY > 0;

  // Areas past `maxAreas` are not dropped: each reason's rest grows one box around them.
  const areas = [];
  const overflow = new Map();
  const add = (box, reason, source) => {
    if (!inCapture(box)) return;
    const x = box.left + scrollX;
    const y = box.top + scrollY;
    if (areas.length < maxAreas) {
      areas.push({ x, y, w: box.width, h: box.height, r: reason, s: source });
      return;
    }
    const rest = overflow.get(reason);
    if (!rest) {
      overflow.set(reason, { x0: x, y0: y, x1: x + box.width, y1: y + box.height, s: source });
    } else {
      rest.x0 = Math.min(rest.x0, x);
      rest.y0 = Math.min(rest.y0, y);
      rest.x1 = Math.max(rest.x1, x + box.width);
      rest.y1 = Math.max(rest.y1, y + box.height);
    }
  };
  const addElement = (el, reason, source) => {
    if (isVisible(el)) add(el.getBoundingClientRect(), reason, source);
  };

  const rejected = [];
  for (const { css, reason, source } of selectors) {
    let found;
    try {
      found = all(css);
    } catch (e) {
      rejected.push(css);
      continue;
    }
    for (const el of found.slice(0, maxPerSelector)) addElement(el, reason, source);
  }

  for (const el of all('[data-petek-mask]')) {
    addElement(el, 'MARKUP', (el.getAttribute('data-petek-mask') || '').trim().slice(0, 60) || 'data-petek-mask');
  }

  for (const el of all('video, object, embed, iframe')) {
    if (el.tagName === 'IFRAME') {
      const src = (el.getAttribute('src') || '').trim();
      if (el.hasAttribute('srcdoc') || src === '' || src === 'about:blank') continue;
      let own;
      try {
        own = el.contentDocument !== null;
      } catch (e) {
        own = false;
      }
      if (own) continue;
    }
    addElement(el, 'EMBED', el.tagName.toLowerCase());
  }

  for (const el of all('time')) addElement(el, 'TIME_TEXT', 'time');

  // The run's texts as one pattern, the longest first; the group that matched names the text's kind.
  const escape = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const runs = (runTexts || [])
    .filter((run) => run && typeof run.text === 'string' && run.text.trim().length >= 3)
    .map((run) => ({ text: run.text.trim(), kind: String(run.kind) }))
    .sort((a, b) => b.text.length - a.text.length);
  let runPattern = null;
  try {
    if (runs.length > 0) runPattern = new RegExp(runs.map((run) => '(' + escape(run.text) + ')').join('|'), 'giu');
  } catch (e) {
    runPattern = null;
  }
  const runKind = (match) => {
    for (let i = 1; i < match.length; i += 1) if (match[i] !== undefined) return runs[i - 1].kind;
    return 'run_text';
  };
  const runMark = typeof runTag === 'string' && /^[a-z0-9]{1,16}$/i.test(runTag) ? new RegExp('\\b' + runTag + '-\\d+\\b', 'gi') : null;
  const ownPatterns = [];
  if (runPattern) ownPatterns.push({ re: runPattern, reason: 'RUN_TEXT', source: runKind });
  if (runMark) ownPatterns.push({ re: runMark, reason: 'RUN_TEXT', source: () => 'run_mark' });
  // Dates and times all hold a digit: text without one is not searched for them.
  const timePatterns = [
    { re: /\b([01]?\d|2[0-3]):[0-5]\d(:[0-5]\d)?\b/g, reason: 'TIME_TEXT', source: () => 'clock' },
    { re: /\b\d{4}-\d{2}-\d{2}\b/g, reason: 'TIME_TEXT', source: () => 'date' },
    { re: /\b\d{1,2}[./]\d{1,2}[./]\d{2,4}\b/g, reason: 'TIME_TEXT', source: () => 'date' },
    { re: /\b\d{1,2}\s+\p{L}{3,}\s+\d{4}\b/gu, reason: 'TIME_TEXT', source: () => 'date' },
    { re: /(?<![\p{L}\p{N}])\p{L}{3,}\s+\d{1,2},\s+\d{4}\b/gu, reason: 'TIME_TEXT', source: () => 'date' },
  ];

  const skipped = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'TEXTAREA', 'TITLE']);
  for (const root of roots) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const text = node.nodeValue;
      const parent = node.parentElement;
      if (!text || !parent || skipped.has(parent.tagName) || text.trim().length < 3) continue;
      const patterns = /\d/.test(text) ? ownPatterns.concat(timePatterns) : ownPatterns;
      for (const { re, reason, source } of patterns) {
        re.lastIndex = 0;
        for (let match = re.exec(text); match; match = re.exec(text)) {
          if (match[0].length === 0) {
            re.lastIndex += 1;
            continue;
          }
          if (!isVisible(parent)) break;
          const range = document.createRange();
          range.setStart(node, match.index);
          range.setEnd(node, match.index + match[0].length);
          for (const box of range.getClientRects()) add(box, reason, source(match));
        }
      }
    }
  }

  for (const el of all('input, textarea')) {
    const value = el.value;
    if (!value || el.type === 'password' || el.type === 'hidden') continue;
    for (const { re, source } of ownPatterns) {
      re.lastIndex = 0;
      const match = re.exec(value);
      if (match) {
        addElement(el, 'RUN_TEXT', source(match));
        break;
      }
    }
  }

  for (const [reason, rest] of overflow) {
    areas.push({ x: rest.x0, y: rest.y0, w: rest.x1 - rest.x0, h: rest.y1 - rest.y0, r: reason, s: rest.s });
  }

  // Elements a mask could name: a test id, or an id without digits (likely stable), big enough to matter.
  const anchors = [];
  const seen = new Set();
  const stableId = /^[A-Za-z][A-Za-z_-]{1,40}$/;
  for (const el of all('[data-testid], [id]')) {
    if (anchors.length >= maxAnchors) break;
    const testId = el.getAttribute('data-testid');
    const id = el.getAttribute('id');
    let selector = null;
    if (testId) selector = '[data-testid="' + CSS.escape(testId) + '"]';
    else if (id && stableId.test(id)) selector = '#' + CSS.escape(id);
    if (!selector) continue;
    const box = el.getBoundingClientRect();
    if (box.width * box.height < 256 || !inCapture(box) || !isVisible(el)) continue;
    const anchor = { selector, x: box.left + scrollX, y: box.top + scrollY, w: box.width, h: box.height };
    const key = [anchor.selector, anchor.x, anchor.y, anchor.w, anchor.h].join('|');
    if (seen.has(key)) continue;
    seen.add(key);
    anchors.push(anchor);
  }

  const fonts = new Set();
  document.fonts.forEach((face) => {
    if (face.status === 'loaded') fonts.add(face.family.replace(/^["']|["']$/g, '') + ' ' + face.weight + ' ' + face.style);
  });

  const body = document.body;
  const root = document.documentElement;
  const navigation = performance.getEntriesByType('navigation')[0];
  return {
    path: location.pathname,
    status: navigation && navigation.responseStatus > 0 ? navigation.responseStatus : null,
    pageHeight: Math.max(
      root.scrollHeight, root.offsetHeight, root.clientHeight,
      body ? body.scrollHeight : 0, body ? body.offsetHeight : 0, body ? body.clientHeight : 0,
    ),
    userAgent: navigator.userAgent,
    fonts: Array.from(fonts).sort().slice(0, maxFonts),
    areas,
    anchors,
    rejected,
  };
};
