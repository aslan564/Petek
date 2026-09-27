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

// What a visitor can check on the page without acting on it: title, main headings, description, language, images,
// visible links with their absolute address, and in-page anchors that name no element. Reads only.
() => {
  const visible = (el) => {
    const box = el.getBoundingClientRect();
    const style = getComputedStyle(el);
    return box.width > 0 && box.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
  };
  const text = (el) => (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 200);
  const meta = document.querySelector('meta[name="description" i]');
  const images = Array.from(document.images).slice(0, 300)
    .filter((img) => img.currentSrc || img.getAttribute('src'))
    .map((img) => ({
      src: img.currentSrc || img.src,
      alt: img.hasAttribute('alt') ? img.getAttribute('alt') : null,
      // An image still loading (lazy, below the fold) is not broken; one that finished without pixels is.
      loaded: !img.complete || img.naturalWidth > 0,
    }));
  const links = Array.from(document.querySelectorAll('a[href]')).filter(visible).slice(0, 500)
    .map((a) => ({ text: text(a) || a.getAttribute('aria-label') || a.getAttribute('title') || '', url: a.href }));
  const missing = new Set();
  for (const a of document.querySelectorAll('a[href^="#"]')) {
    let id = a.getAttribute('href').slice(1);
    try { id = decodeURIComponent(id); } catch (e) { /* kept as written */ }
    if (!id || id === 'top') continue;
    if (!document.getElementById(id) && document.getElementsByName(id).length === 0) missing.add('#' + id);
  }
  return {
    title: document.title || '',
    headings: Array.from(document.querySelectorAll('h1')).filter(visible).map(text).filter((t) => t.length > 0),
    description: meta ? (meta.getAttribute('content') || '') : null,
    language: document.documentElement.getAttribute('lang'),
    images,
    links,
    missingAnchors: Array.from(missing),
  };
}
