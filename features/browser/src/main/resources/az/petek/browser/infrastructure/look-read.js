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
// mask could name. Boxes are in page coordinates (CSS px; the page is at its top) and count only where they show
// (see `shownArea`) within the captured height:
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

  // Drawn at all: a box, not hidden, not fully transparent, nor in a subtree that is (`content-visibility: auto` content
  // off the screen counts as drawn: a full-page frame may draw it).
  const visibility = new Map();
  const isVisible = (el) => {
    if (!visibility.has(el)) {
      visibility.set(el, typeof el.checkVisibility !== 'function' ||
        el.checkVisibility({ checkVisibilityCSS: true, visibilityProperty: true, checkOpacity: true, opacityProperty: true }));
    }
    return visibility.get(el);
  };

  // Where a box shows, in viewport coordinates: an ancestor's overflow (or paint containment) clips the boxes laid out
  // in it, which an absolutely positioned box is only when the ancestor is positioned (a fixed one: when it holds fixed
  // boxes, as a transform does); `clip-path` and `clip` clip all they draw. A collapsed menu, a closed accordion, a
  // scrolled-away part or a screen-reader-only text thus shows nowhere, though its text has boxes.
  const EVERYWHERE = { left: -Infinity, top: -Infinity, right: Infinity, bottom: Infinity };
  const meet = (a, b) => ({
    left: Math.max(a.left, b.left), top: Math.max(a.top, b.top), right: Math.min(a.right, b.right), bottom: Math.min(a.bottom, b.bottom),
  });
  const flatParent = (node) =>
    node.assignedSlot || node.parentElement || (node.parentNode instanceof ShadowRoot ? node.parentNode.host : null);
  const styles = new Map();
  const style = (el) => {
    if (!styles.has(el)) styles.set(el, getComputedStyle(el));
    return styles.get(el);
  };
  const positioning = (cs) => (cs.position === 'absolute' || cs.position === 'fixed' ? cs.position : 'flow');
  const holdsFixed = (cs) => cs.transform !== 'none' || cs.perspective !== 'none' || cs.filter !== 'none' ||
    (cs.backdropFilter || 'none') !== 'none' || /paint|layout|strict|content/.test(cs.contain || '') ||
    /transform|perspective|filter/.test(cs.willChange || '') || (cs.containerType || 'normal') !== 'normal';
  const holds = (cs, how) => how === 'flow' || (how === 'fixed' ? holdsFixed(cs) : cs.position !== 'static' || holdsFixed(cs));
  const root = document.documentElement;
  // The root's overflow is the screen's, and so is the body's while the root's is visible: neither clips the page.
  const bodyOverflowIsScreens = style(root).overflowX === 'visible' && style(root).overflowY === 'visible';
  const px = (value, size) => {
    const v = (value || '').trim();
    if (/^-?[\d.]+px$/.test(v) || v === '0') return parseFloat(v);
    if (/^-?[\d.]+%$/.test(v)) return (parseFloat(v) * size) / 100;
    return NaN;
  };
  const sure = (area, box) => (Object.values(area).some(Number.isNaN) ? box : area);
  const paintClip = (el, cs) => {
    let area = EVERYWHERE;
    if (cs.clipPath && cs.clipPath !== 'none' && !cs.clipPath.startsWith('url(')) {
      const b = el.getBoundingClientRect();
      const box = { left: b.left, top: b.top, right: b.right, bottom: b.bottom };
      const inset = /inset\(([^)]*)\)/.exec(cs.clipPath);
      if (inset) {
        // inset(top right bottom left [round …]), as the margin shorthand; another shape stays within the box.
        const [t, r = t, bo = t, l = r] = inset[1].split(/\s+round\s+/)[0].trim().split(/\s+/);
        area = meet(area, sure({
          left: b.left + px(l, b.width), top: b.top + px(t, b.height), right: b.right - px(r, b.width), bottom: b.bottom - px(bo, b.height),
        }, box));
      } else {
        area = meet(area, box);
      }
    }
    const rect = cs.position === 'absolute' || cs.position === 'fixed' ? /rect\(([^)]*)\)/.exec(cs.clip || '') : null;
    if (rect) {
      // rect(top, right, bottom, left): offsets from the box's top left corner; `auto` is the box's own edge.
      const b = el.getBoundingClientRect();
      const [t, r, bo, l] = rect[1].split(/[\s,]+/).filter(Boolean);
      const at = (v, edge) => (v === undefined || v === 'auto' ? edge : px(v, 0));
      area = meet(area, sure({
        left: b.left + at(l, 0), top: b.top + at(t, 0), right: b.left + at(r, b.width), bottom: b.top + at(bo, b.height),
      }, { left: b.left, top: b.top, right: b.right, bottom: b.bottom }));
    }
    return area;
  };
  const overflowClip = (el, cs) => {
    if (el === root || (el === document.body && bodyOverflowIsScreens)) return EVERYWHERE;
    const contained = /paint|strict|content/.test(cs.contain || '') || cs.contentVisibility === 'auto';
    const x = contained || cs.overflowX !== 'visible';
    const y = contained || cs.overflowY !== 'visible';
    if (!x && !y) return EVERYWHERE;
    const b = el.getBoundingClientRect();
    return {
      left: x ? b.left + (parseFloat(cs.borderLeftWidth) || 0) : -Infinity,
      top: y ? b.top + (parseFloat(cs.borderTopWidth) || 0) : -Infinity,
      right: x ? b.right - (parseFloat(cs.borderRightWidth) || 0) : Infinity,
      bottom: y ? b.bottom - (parseFloat(cs.borderBottomWidth) || 0) : Infinity,
    };
  };
  // Where a box laid out in [el] (`flow`, `absolute` or `fixed`) shows, through [el] and every ancestor above it.
  const within = new Map();
  const shownIn = (el, how) => {
    if (!el) return EVERYWHERE;
    let known = within.get(el);
    if (!known) within.set(el, (known = {}));
    if (known[how]) return known[how];
    const cs = style(el);
    let area;
    if (cs.display === 'contents') {
      area = shownIn(flatParent(el), how);
    } else if (holds(cs, how)) {
      area = meet(meet(paintClip(el, cs), overflowClip(el, cs)), shownIn(flatParent(el), positioning(cs)));
    } else {
      area = meet(paintClip(el, cs), shownIn(flatParent(el), how));
    }
    known[how] = area;
    return area;
  };
  // An element's box, or a text's, cut to where it shows.
  const shownArea = (box, area) => {
    const left = Math.max(box.left, area.left);
    const top = Math.max(box.top, area.top);
    const right = Math.min(box.right, area.right);
    const bottom = Math.min(box.bottom, area.bottom);
    return { left, top, right, bottom, width: Math.max(0, right - left), height: Math.max(0, bottom - top) };
  };
  const elementArea = (el) => {
    const cs = style(el);
    return shownArea(el.getBoundingClientRect(), meet(paintClip(el, cs), shownIn(flatParent(el), positioning(cs))));
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
    if (isVisible(el)) add(elementArea(el), reason, source);
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
      const parent = node.parentElement || (node.parentNode instanceof ShadowRoot ? node.parentNode.host : null);
      if (!text || !parent || skipped.has(parent.tagName) || text.trim().length < 3) continue;
      const patterns = /\d/.test(text) ? ownPatterns.concat(timePatterns) : ownPatterns;
      let area = null;
      for (const { re, reason, source } of patterns) {
        re.lastIndex = 0;
        for (let match = re.exec(text); match; match = re.exec(text)) {
          if (match[0].length === 0) {
            re.lastIndex += 1;
            continue;
          }
          if (!isVisible(parent)) break;
          // A text is laid out in the element it is shown in (a slot, for one put into a shadow root).
          if (area === null) area = shownIn(node.assignedSlot || parent, 'flow');
          const range = document.createRange();
          range.setStart(node, match.index);
          range.setEnd(node, match.index + match[0].length);
          for (const box of range.getClientRects()) add(shownArea(box, area), reason, source(match));
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
    if (!selector || !isVisible(el)) continue;
    const box = elementArea(el);
    if (box.width * box.height < 256 || !inCapture(box)) continue;
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
