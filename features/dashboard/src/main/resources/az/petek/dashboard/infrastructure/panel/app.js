/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

/* Pətək panel — start-up: sidebar navigation, run and exploration status, theme, mobile menu, first screen. */
(() => {
  'use strict';
  const P = window.Petek;
  const { h, L, fmt } = P;
  const app = P.$('app');
  const navItems = new Map();

  // ---------- sidebar ----------
  const groups = new Map();
  for (const def of P.screens()) {
    if (!groups.has(def.group)) groups.set(def.group, []);
    groups.get(def.group).push(def);
  }
  for (const [group, defs] of groups) {
    const box = h('div', 'nav-group', h('div', { class: 'nav-title', text: group }));
    for (const def of defs) {
      const badge = h('span', { class: 'nav-badge', hidden: true });
      const item = h('a', { class: 'nav-item', attrs: { href: '#/' + def.id, title: def.title }, data: { screen: def.id } },
        P.icon(def.icon), h('span', { class: 'label', text: def.title }), badge);
      navItems.set(def.id, { item, badge, def });
      box.append(item);
    }
    P.$('nav').append(box);
  }

  function updateBadges() {
    for (const { badge, def } of navItems.values()) {
      const b = def.badge ? def.badge(P.run.header) : null;
      badge.hidden = !b;
      if (b) { badge.textContent = b.text; badge.className = 'nav-badge' + (b.tone ? ' ' + b.tone : ''); badge.title = b.title || ''; }
    }
  }

  // ---------- status cards (run and exploration) ----------
  const runCard = {
    badge: h('span'), title: h('div', 'title ellipsis'), meta: h('div', 'meta'),
  };
  runCard.el = h('button', { class: 'status-card', attrs: { type: 'button' }, on: { click: () => P.go('agentler') } },
    h('div', 'row', h('span', { class: 'kicker', text: 'Run' }), runCard.badge), runCard.title, runCard.meta);
  const exploreCard = {
    badge: h('span'), title: h('div', 'title ellipsis'), meta: h('div', 'meta'),
  };
  exploreCard.el = h('button', { class: 'status-card', attrs: { type: 'button' }, on: { click: () => P.go('kesfiyyat') } },
    h('div', 'row', h('span', { class: 'kicker', text: 'Kəşfiyyat' }), exploreCard.badge), exploreCard.title, exploreCard.meta);
  P.$('sideStatus').append(exploreCard.el, runCard.el);

  const EXPLORE_TONE = { RUNNING: 'blue', FINISHED: 'green', TIMED_OUT: 'amber', FAILED: 'red', CANCELLED: 'slate' };

  function renderStatus() {
    const header = P.run.header;
    const run = header && header.run;
    const [tone, text, live] = P.runStatus(run);
    P.fill(runCard.badge, P.badge(tone, text, { dot: true, live }));
    runCard.title.textContent = run && run.runId ? run.campaignName || run.runId : 'Aktiv run yoxdur';
    if (run && run.runId) {
      const k = header.counters;
      runCard.meta.textContent = fmt.duration(P.elapsed(header)) + ' · ' + k.agents + ' agent' + (k.findings ? ' · ' + k.findings + ' tapıntı' : '');
    } else runCard.meta.textContent = 'Təlimatdan run başladın';
    const e = P.run.jobs && P.run.jobs.exploration;
    exploreCard.el.hidden = !e;
    if (e) {
      P.fill(exploreCard.badge, P.badge(EXPLORE_TONE[e.status] || 'slate', L.explorationStatus[e.status] || e.status, { dot: true, live: e.status === 'RUNNING' }));
      exploreCard.title.textContent = e.target ? fmt.host(e.target) : 'Kəşfiyyat';
      exploreCard.meta.textContent = e.pagesVisited + ' / ' + e.maxPages + ' səhifə' + (e.phase ? ' · ' + (L.phase[e.phase] || e.phase).toLowerCase() : '');
    }
    const chip = P.$('runChip');
    chip.hidden = !(run && run.runId);
    if (run && run.runId) {
      P.fill(chip, P.badge(tone, text, { dot: true, live }), h('span', { class: 'name ellipsis', text: run.campaignName || run.runId }), h('span', { class: 't', text: fmt.duration(P.elapsed(header)) }));
      chip.title = 'Canlı lövhəyə keç';
    }
    updateBadges();
  }
  P.onRun(renderStatus);
  P.onTick(() => {
    const header = P.run.header;
    if (!header || !header.run || header.run.phase !== 'RUNNING') return;
    const t = P.$('runChip').querySelector('.t');
    if (t) t.textContent = fmt.duration(P.elapsed(header));
    const k = header.counters;
    runCard.meta.textContent = fmt.duration(P.elapsed(header)) + ' · ' + k.agents + ' agent' + (k.findings ? ' · ' + k.findings + ' tapıntı' : '');
  });
  P.$('runChip').addEventListener('click', () => P.go('agentler'));

  // ---------- theme ----------
  const THEMES = [['auto', 'monitor', 'Sistem'], ['light', 'sun', 'İşıqlı'], ['dark', 'moon', 'Qaranlıq']];
  const media = window.matchMedia ? matchMedia('(prefers-color-scheme: dark)') : null;
  function applyTheme(pref) {
    const dark = pref === 'dark' || (pref === 'auto' && media && media.matches);
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    document.documentElement.dataset.themePref = pref;
    for (const b of P.$('themeSwitch').children) b.setAttribute('aria-pressed', String(b.dataset.theme === pref));
  }
  for (const [pref, icon, label] of THEMES) {
    P.$('themeSwitch').append(h('button', {
      attrs: { type: 'button', title: label, 'aria-label': label }, data: { theme: pref },
      on: { click: () => { try { localStorage.setItem('petek.theme', pref); } catch (e) { /* storage blocked */ } applyTheme(pref); } },
    }, P.icon(icon, 'sm')));
  }
  P.$('themeSwitch').before(h('span', { class: 'kicker', text: 'Görünüş' }));
  applyTheme(document.documentElement.dataset.themePref || 'auto');
  if (media && media.addEventListener) media.addEventListener('change', () => applyTheme(document.documentElement.dataset.themePref || 'auto'));

  // ---------- mobile menu ----------
  function setMenu(open) {
    app.classList.toggle('menu-open', open);
    P.$('scrim').hidden = !open;
  }
  P.$('menuBtn').append(P.icon('menu'));
  P.$('sidebarClose').append(P.icon('x'));
  P.$('menuBtn').addEventListener('click', () => setMenu(true));
  P.$('sidebarClose').addEventListener('click', () => setMenu(false));
  P.$('scrim').addEventListener('click', () => setMenu(false));
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') setMenu(false); });

  P.onRoute = (def) => {
    for (const [id, { item }] of navItems) {
      if (id === def.id) item.setAttribute('aria-current', 'page'); else item.removeAttribute('aria-current');
    }
    setMenu(false);
  };

  // ---------- "Kodu daxil et": a tester waits for a code the owner types in (PETEK_MAIL_SOURCE=manual) ----------
  const codeBox = h('section', { class: 'manual-codes', hidden: true, attrs: { 'aria-live': 'polite' } });
  document.body.append(codeBox);
  const typed = new Map();
  async function pollCodes() {
    const res = await P.api.get('/api/manual-codes');
    const requests = res.ok && res.data && Array.isArray(res.data.requests) ? res.data.requests : [];
    codeBox.hidden = requests.length === 0;
    codeBox.replaceChildren(h('div', { class: 'title', text: 'Kodu daxil et' }),
      h('div', { class: 'hint', text: 'Tester təsdiq kodunu gözləyir. Kodu öz poçtunuzdan və ya telefonunuzdan oxuyub yazın.' }));
    for (const r of requests) {
      const input = h('input', { attrs: { type: 'text', inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: 16, 'aria-label': 'Kod: ' + r.address } });
      input.value = typed.get(r.id) || '';
      input.addEventListener('input', () => typed.set(r.id, input.value));
      const send = async () => {
        const answer = await P.api.post('/api/manual-codes/' + encodeURIComponent(r.id), { code: input.value });
        if (answer.ok) { typed.delete(r.id); P.toast('Kod testerə verildi.', 'ok'); pollCodes(); }
        else P.toast((answer.problems[0] && answer.problems[0].message) || answer.error, 'error');
      };
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter') send(); });
      codeBox.append(h('div', 'row', h('span', { class: 'address ellipsis', text: r.address, title: r.address }), input,
        h('button', { class: 'btn primary', text: 'Göndər', attrs: { type: 'button' }, on: { click: send } })));
    }
  }
  pollCodes();
  setInterval(pollCodes, 3000);

  // ---------- first screen: the live board while a run goes on, the instructions otherwise ----------
  let firstRun = null;
  P.defaultScreen = () => (firstRun && firstRun.phase === 'RUNNING' ? 'agentler' : 'telimat');
  renderStatus();
  if (location.hash.length > 2) P.startRouter();
  else {
    P.api.get('/api/snapshot').then((res) => {
      if (res.ok) {
        firstRun = res.data.run;
        P.syncTime(res.data.generatedAtMs);
      }
      P.startRouter();
    });
  }
})();
