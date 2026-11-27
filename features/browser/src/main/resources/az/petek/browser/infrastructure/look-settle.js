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

// Brings the page to rest before a look is taken (PlaywrightBrowserSession.look): at its top, nothing focused or
// selected, web fonts loaded, lazy content of the part to capture loaded by scrolling through it, the images there
// decoded, and the network quiet (no resource finished for `quietMs`, seen by a PerformanceObserver, which the
// resource buffer's size does not limit). Every wait shares the `settleMs` budget; what had not finished by then is
// reported, not waited for. Only the scroll position, focus and selection change: nothing is added to the page.
async ({ settleMs, maxHeight, fontsMs, quietMs, stepMs }) => {
  const started = performance.now();
  const left = () => Math.max(0, settleMs - (performance.now() - started));
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));
  // True when [promise] settles within [ms]; a promise settled already wins even at 0 ms (microtasks run first).
  const within = (promise, ms) =>
    Promise.race([promise.then(() => true, () => true), sleep(ms).then(() => false)]);
  const nextFrame = () => within(new Promise((resolve) => requestAnimationFrame(() => resolve())), stepMs);
  const toTop = () => window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
  const pageHeight = () => {
    const body = document.body;
    const root = document.documentElement;
    return Math.max(
      root.scrollHeight, root.offsetHeight, root.clientHeight,
      body ? body.scrollHeight : 0, body ? body.offsetHeight : 0, body ? body.clientHeight : 0,
    );
  };
  // The captured height as PlaywrightBrowserSession.look clips it: at least the first screen.
  const captureHeight = () => (maxHeight > 0 ? Math.max(innerHeight, Math.min(pageHeight(), maxHeight)) : innerHeight);

  let lastResource = performance.now();
  let observer = null;
  try {
    observer = new PerformanceObserver((list) => {
      if (list.getEntries().length > 0) lastResource = performance.now();
    });
    observer.observe({ type: 'resource' });
  } catch (e) {
    observer = null;
  }
  try {
    toTop();
    const focused = document.activeElement;
    if (focused && focused !== document.body && typeof focused.blur === 'function') focused.blur();
    const selection = window.getSelection ? window.getSelection() : null;
    if (selection) selection.removeAllRanges();

    await within(document.fonts.ready, Math.min(fontsMs, left()));
    if (maxHeight > 0) {
      // One screen at a time, so lazy images and content below the fold load; the capture grows with the page.
      for (let y = innerHeight; y < captureHeight() && left() > 0; y += innerHeight) {
        window.scrollTo({ top: y, left: 0, behavior: 'instant' });
        await nextFrame();
        await sleep(Math.min(stepMs, left()));
      }
      toTop();
      await nextFrame();
    }
    // Content shown while scrolling may have asked for more fonts.
    const fontsReady = (await within(document.fonts.ready, Math.min(fontsMs, left()))) && document.fonts.status === 'loaded';

    const limit = captureHeight();
    const inCapture = (img) => {
      const box = img.getBoundingClientRect();
      const top = box.top + scrollY;
      return box.width > 0 && box.height > 0 && top < limit && top + box.height > 0;
    };
    const images = Array.from(document.images).filter(inCapture);
    let pending = images.filter((img) => !img.complete);
    while (pending.length > 0 && left() > 0) {
      await sleep(Math.min(50, left()));
      pending = pending.filter((img) => !img.complete);
    }
    const loaded = images.filter((img) => img.complete && img.naturalWidth > 0);
    await within(Promise.all(loaded.map((img) => img.decode().catch(() => null))), left());

    while (performance.now() - lastResource < quietMs && left() > 0) {
      await sleep(Math.min(quietMs - (performance.now() - lastResource), left()));
    }
    const quiet = observer === null || performance.now() - lastResource >= quietMs;
    return { fontsReady, pendingImages: pending.length, quiet, pageHeight: pageHeight(), width: innerWidth, height: innerHeight };
  } finally {
    if (observer) observer.disconnect();
    toTop();
  }
};
