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

// Starts watching the page for a text under a key and returns { before, armedAt }: `before` is true when the text is
// visible already (then nothing is watched), `armedAt` the page's `performance.now()` at that moment. Otherwise the
// page times the text's first appearance itself (`seenAt`, same clock), so no call from outside is needed while the
// receiver waits. Text matching is the visibility probe's, embedded below by the adapter.
//
// A DOM change schedules a check at once (at most one per `gapMs`); a poll every `pollMs` catches what no DOM change
// announces (a style sheet, an animation, a shadow root). The watch ends when the text is seen, when it is read with
// text-watch-read.js, after `maxMs`, or with the page itself (a navigation replaces the window and everything in it).
({ key, text, gapMs, pollMs, maxMs }) => {
  const probe = __VISIBILITY_PROBE__;
  const watches = (window.__petekTextWatches = window.__petekTextWatches || {});
  if (watches[key]) watches[key].stop();

  const armedAt = performance.now();
  const visible = () => probe({ text, selector: null });
  if (visible()) {
    watches[key] = { armedAt, before: true, seenAt: null, stop: () => {} };
    return { before: true, armedAt };
  }

  const watch = { armedAt, before: false, seenAt: null };
  let pending = null;
  let lastCheck = armedAt;
  const check = () => {
    pending = null;
    lastCheck = performance.now();
    if (watch.seenAt === null && visible()) {
      watch.seenAt = performance.now();
      watch.stop();
    }
  };
  const schedule = () => {
    if (pending !== null || watch.seenAt !== null) return;
    pending = setTimeout(check, Math.max(0, lastCheck + gapMs - performance.now()));
  };
  const observer = new MutationObserver(schedule);
  observer.observe(document, { subtree: true, childList: true, characterData: true, attributes: true });
  const poll = setInterval(check, pollMs);
  const cap = setTimeout(() => watch.stop(), maxMs);
  watch.stop = () => {
    observer.disconnect();
    clearInterval(poll);
    clearTimeout(cap);
    if (pending !== null) clearTimeout(pending);
    pending = null;
  };
  watches[key] = watch;
  return { before: false, armedAt };
}
