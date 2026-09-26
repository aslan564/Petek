/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

/* Agentlər — the live board: who is doing what right now, with live screenshots, filters, timeline and findings. */
(() => {
  'use strict';
  const P = window.Petek;
  const { h, L, fmt } = P;
  const COMPACT_OVER = 60;
  const TIMELINE_MAX = 300;

  let snap = null;
  const cards = new Map();
  const filters = { states: new Set(), roles: new Set(), q: '' };
  let density = 'auto';
  try { density = localStorage.getItem('petek.density') || 'auto'; } catch (e) { /* storage blocked */ }
  let ui = null;
  let timelineRun = null;
  let lastSeq = 0;
  let findingsSig = '';
  let selected = null;
  let detailSig = '';
  let detailTimer = null;
  let lastFocus = null;
  let visible = false;

  const artifactUrl = (id) => '/artifacts/' + encodeURIComponent(id);

  // ---------- lazy screenshots: only cards near the viewport load their picture ----------
  const observer = 'IntersectionObserver' in window
    ? new IntersectionObserver((entries) => {
      for (const entry of entries) {
        const c = entry.target.card;
        if (!c) continue;
        c.inView = entry.isIntersecting;
        if (c.inView) { loadShot(c); c.ago.textContent = fmt.ago(c.data && c.data.lastActionAtMs); }
      }
    }, { rootMargin: '300px 0px' })
    : null;

  function loadShot(c) {
    const id = c.data && c.data.screenshot;
    if (!id || c.shown === id || c.loading === id) return;
    c.loading = id;
    const url = artifactUrl(id);
    const next = new Image();
    next.decoding = 'async';
    next.onload = () => {
      if (c.loading !== id) return;
      c.img.src = url;
      c.shown = id;
      c.loading = null;
      c.thumb.classList.add('has-shot');
    };
    next.onerror = () => { if (c.loading === id) c.loading = null; };
    next.src = url;
  }

  // ---------- cards ----------
  function createCard(a) {
    const img = h('img', { attrs: { alt: '' } });
    const pillText = h('span');
    const failBadge = h('span', 'fail-badge');
    const thumb = h('div', 'thumb', img, h('span', { class: 'thumb-none', text: 'Screenshot yoxdur' }), h('span', 'pill', h('i'), pillText), failBadge);
    const avatar = h('span', 'avatar');
    const name = h('div', 'agent-name');
    const aid = h('span', 'mono');
    const role = h('span', 'role');
    const dept = h('div', 'dept');
    const scn = h('span', 'scn');
    const ago = h('span', 'ago');
    const action = h('div', 'action mono');
    const acts = h('span');
    const fails = h('span', 'fails');
    const reason = h('div', 'reason');
    const el = h('article', { class: 'agent', attrs: { tabindex: '0', role: 'button' }, data: { agent: a.id } },
      thumb,
      h('div', 'agent-body',
        h('div', 'agent-top', avatar, h('div', 'who', name, h('div', 'agent-sub', aid, role))),
        dept, h('div', 'step-line', scn, ago), action, h('div', 'agent-foot', acts, fails), reason));
    const c = { el, thumb, img, pillText, failBadge, avatar, name, aid, role, dept, scn, ago, action, acts, fails, reason, data: null, sig: '', inView: !observer, shown: null, loading: null };
    thumb.card = c;
    el.addEventListener('click', () => openAgent(a.id));
    el.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openAgent(a.id); } });
    if (observer) observer.observe(thumb);
    return c;
  }

  function updateCard(c, a) {
    const sig = [a.name, a.role, a.department, a.registration, a.state, a.step, a.lastAction, a.lastActionAtMs, a.screenshot, a.actions, a.failures, a.lastFailure].join('\u0001');
    if (sig === c.sig) return;
    const acted = c.data && (c.data.lastActionAtMs !== a.lastActionAtMs || c.data.state !== a.state);
    c.sig = sig;
    c.data = a;
    c.el.className = 'agent st-' + a.state;
    c.el.dataset.state = a.state;
    if (a.screenshot) c.el.dataset.screenshot = a.screenshot; else delete c.el.dataset.screenshot;
    c.el.setAttribute('aria-label', a.name + ' (' + a.id + '), ' + (L.agentState[a.state] || a.state));
    c.name.textContent = a.name;
    c.name.title = a.name;
    c.aid.textContent = a.id;
    c.avatar.textContent = fmt.initials(a.name);
    c.avatar.style.setProperty('--h', fmt.hue(a.name));
    c.role.textContent = L.role[a.role] || 'Rol məlum deyil';
    c.role.className = 'role role-' + (a.role || 'none');
    const dept = [a.department || (a.role === 'admin' ? 'Rəhbərlik' : null), L.registration[a.registration]].filter(Boolean).join(' · ');
    c.dept.textContent = dept || '—';
    c.pillText.textContent = L.agentState[a.state] || a.state;
    c.failBadge.textContent = a.failures ? a.failures + ' xəta' : '';
    c.failBadge.hidden = !a.failures;
    c.scn.textContent = a.step || 'addım yoxdur';
    c.action.textContent = a.lastAction || 'Hələ əməliyyat yoxdur';
    c.action.classList.toggle('empty-action', !a.lastAction);
    c.action.title = a.lastAction || '';
    c.acts.textContent = a.actions + ' əməliyyat';
    c.fails.textContent = a.failures ? '· ' + a.failures + ' xəta' : '';
    c.reason.textContent = a.lastFailure || '';
    c.reason.hidden = !a.lastFailure;
    c.ago.textContent = fmt.ago(a.lastActionAtMs);
    if (c.inView) loadShot(c);
    if (acted && c.inView && visible) {
      c.el.classList.remove('flash');
      void c.el.offsetWidth;
      c.el.classList.add('flash');
    }
  }

  function renderAgents(s) {
    const ids = s.agents.map((a) => a.id);
    let reorder = ids.length !== cards.size;
    const seen = new Set(ids);
    for (const [id, c] of cards) {
      if (!seen.has(id)) { if (observer) observer.unobserve(c.thumb); c.el.remove(); cards.delete(id); reorder = true; }
    }
    for (const a of s.agents) {
      let c = cards.get(a.id);
      if (!c) { c = createCard(a); cards.set(a.id, c); reorder = true; }
      updateCard(c, a);
    }
    if (reorder) {
      const frag = document.createDocumentFragment();
      for (const id of ids) frag.append(cards.get(id).el);
      ui.grid.append(frag);
    }
    applyDensity();
    applyFilters();
  }

  function effectiveDensity() { return density === 'auto' ? (cards.size > COMPACT_OVER ? 'compact' : 'cards') : density; }
  function applyDensity() {
    const mode = effectiveDensity();
    ui.grid.classList.toggle('compact', mode === 'compact');
    ui.densityCards.setAttribute('aria-pressed', String(mode === 'cards'));
    ui.densityCompact.setAttribute('aria-pressed', String(mode === 'compact'));
  }
  function setDensity(mode) {
    density = mode;
    try { localStorage.setItem('petek.density', mode); } catch (e) { /* storage blocked */ }
    applyDensity();
  }

  function matches(a) {
    if (filters.states.size && !filters.states.has(a.state)) return false;
    if (filters.roles.size && !filters.roles.has(a.role || '')) return false;
    if (filters.q) {
      const hay = [a.name, a.id, a.department, a.step, a.lastAction].filter(Boolean).join(' ').toLocaleLowerCase('az');
      if (!hay.includes(filters.q)) return false;
    }
    return true;
  }

  function applyFilters() {
    let shown = 0;
    for (const c of cards.values()) {
      const ok = matches(c.data);
      c.el.hidden = !ok;
      if (ok) shown++;
    }
    ui.shown.textContent = cards.size ? shown + ' / ' + cards.size + ' agent' : '';
    const noRun = !snap || !snap.run.runId;
    ui.empty.hidden = shown > 0;
    ui.emptyTitle.textContent = cards.size ? 'Uyğun agent yoxdur' : noRun ? 'Run gözlənilir' : 'Agentlər hazırlanır';
    ui.emptyText.textContent = cards.size ? 'Filtrləri dəyişin və ya axtarışı təmizləyin.' : 'Run başlayanda hər tester burada öz kartı ilə görünəcək.';
  }

  function chip(label, key, cssVar, set) {
    const n = h('span', { class: 'n', text: '0' });
    const b = h('button', { class: 'chip', attrs: { type: 'button', 'aria-pressed': 'false' }, data: { key } });
    if (cssVar) { b.style.setProperty('--c', 'var(' + cssVar + ')'); b.append(h('span', 'dot')); }
    b.append(h('span', { text: label }), n);
    b.addEventListener('click', () => {
      if (set.has(key)) set.delete(key); else set.add(key);
      b.setAttribute('aria-pressed', String(set.has(key)));
      applyFilters();
    });
    return { b, n };
  }

  // ---------- tiles ----------
  function setPair(box, value, count) { value.textContent = fmt.int(count); box.classList.toggle('zero', count === 0); }

  function renderTiles(s) {
    const k = s.counters;
    ui.agentsTotal.textContent = fmt.int(k.agents);
    L.agentStates.forEach((st, i) => {
      const n = k.agentsByState[st] || 0;
      ui.stateBar.children[i].style.setProperty('width', (k.agents ? (100 * n) / k.agents : 0) + '%');
      ui.legend.children[i].lastChild.textContent = n;
      ui.stateChips[st].n.textContent = n;
      ui.stateChips[st].b.classList.toggle('zero', n === 0);
    });
    const roleCounts = { admin: 0, manager: 0, employee: 0 };
    for (const a of s.agents) if (a.role in roleCounts) roleCounts[a.role]++;
    for (const r of Object.keys(roleCounts)) {
      ui.roleChips[r].n.textContent = roleCounts[r];
      ui.roleChips[r].b.classList.toggle('zero', roleCounts[r] === 0);
    }
    setPair(ui.stepsOk.box, ui.stepsOk.value, k.stepsPassed);
    setPair(ui.stepsBad.box, ui.stepsBad.value, k.stepsFailed);
    setPair(ui.asOk.box, ui.asOk.value, k.assertionsPassed);
    setPair(ui.asBad.box, ui.asBad.value, k.assertionsFailed);
    ui.asSkip.textContent = fmt.int(k.assertionsSkipped);
    ui.events.textContent = fmt.int(k.events);
    setPair(ui.rcOk.box, ui.rcOk.value, k.receiptsReceived);
    setPair(ui.rcBad.box, ui.rcBad.value, k.receiptsMissing);
    const receipts = k.receiptsReceived + k.receiptsMissing;
    ui.rcBar.style.setProperty('width', (receipts ? (100 * k.receiptsReceived) / receipts : 0) + '%');
    ui.findings.textContent = fmt.int(k.findings);
    ui.findTile.classList.toggle('alert', k.findings > 0);
    ui.findBadge.textContent = k.findings;
    ui.findBadge.classList.toggle('hot', k.findings > 0);
  }

  // ---------- timeline and findings ----------
  function agentName(id) { const c = cards.get(id); return c && c.data ? c.data.name : id; }

  function agentLink(id) {
    return h('button', { class: 'agent-link', text: id, title: agentName(id), attrs: { type: 'button' }, on: { click: () => openAgent(id) } });
  }

  function entryEl(e, withAgent, fresh) {
    const head = h('div', 'entry-head', h('span', { class: 'kind', text: L.timelineKind[e.kind] || e.kind }));
    if (withAgent && e.agentId) head.append(agentLink(e.agentId));
    if (e.step) head.append(h('span', { class: 'entry-step', text: e.step }));
    return h('li', { class: 'entry ' + e.status.toLowerCase() + (fresh ? ' fresh' : ''), data: { kind: e.kind } },
      h('time', { class: 't mono', text: fmt.clock(e.atMs) }),
      h('div', null, head, h('div', { class: 'entry-text', text: e.text })));
  }

  function renderTimeline(s) {
    const list = ui.timeline;
    const runKey = s.run.runId || '';
    if (runKey !== timelineRun) { list.textContent = ''; lastSeq = 0; timelineRun = runKey; }
    const fresh = [];
    for (const e of s.timeline) { if (e.seq <= lastSeq) break; fresh.push(e); }
    if (fresh.length) {
      const animate = lastSeq > 0;
      const frag = document.createDocumentFragment();
      for (const e of fresh) frag.append(entryEl(e, true, animate));
      list.prepend(frag);
      lastSeq = fresh[0].seq;
      while (list.childElementCount > TIMELINE_MAX) list.lastElementChild.remove();
    }
    ui.timelineEmpty.hidden = list.childElementCount > 0;
  }

  function source(label, value) { return [h('span', { class: 'src', text: label }), h('span', { class: value ? 'src-v' : 'src-v none', text: value || '—' })]; }

  function findingEl(f) {
    const head = h('div', 'finding-head', h('span', { class: 'fclass', text: L.findingClass[f.findingClass] || f.findingClass }), h('span', { class: 'entry-step', text: f.step }));
    if (f.agentId) head.append(agentLink(f.agentId));
    if (f.evidenceTier) head.append(h('span', { class: 'tier tier-' + f.evidenceTier, text: L.evidenceTier[f.evidenceTier] || f.evidenceTier }));
    const box = h('article', 'finding fc-' + f.findingClass, head, h('div', 'sources', ...source('A', f.a), ...source('B', f.b), ...source('C', f.c)), h('div', { class: 'note', text: f.note }));
    if (f.artifacts.length) {
      box.append(h('div', 'proofs', f.artifacts.map((id, i) => h('a', { text: 'Sübut ' + (i + 1) + ' ↗', attrs: { href: artifactUrl(id), target: '_blank', rel: 'noopener' } }))));
    }
    return box;
  }

  function renderFindings(s) {
    const sig = (s.run.runId || '') + ':' + s.counters.findings + ':' + (s.findings[0] ? s.findings[0].id : '');
    if (sig === findingsSig) return;
    findingsSig = sig;
    P.fill(ui.findingsList, s.findings.map(findingEl));
    ui.findingsEmpty.hidden = s.findings.length > 0;
  }

  function selectTab(name) {
    const timeline = name === 'timeline';
    ui.tabTimeline.setAttribute('aria-selected', String(timeline));
    ui.tabFindings.setAttribute('aria-selected', String(!timeline));
    ui.timelinePane.hidden = !timeline;
    ui.findingsPane.hidden = timeline;
    ui.onlyFailBox.hidden = !timeline;
  }

  // ---------- agent drawer ----------
  const drawer = {};
  function buildDrawer() {
    drawer.avatar = h('span', 'avatar');
    drawer.name = h('h2', { attrs: { id: 'drawerName' } });
    drawer.id = h('span', 'mono');
    drawer.role = h('span', 'role');
    drawer.state = h('span', 'state-tag');
    drawer.close = h('button', { class: 'icon-btn', attrs: { type: 'button', 'aria-label': 'Bağla' }, on: { click: closeAgent } }, P.icon('x'));
    drawer.shot = h('img', { attrs: { alt: 'Son screenshot' } });
    drawer.shotNone = h('span', { class: 'thumb-none', text: 'Screenshot yoxdur' });
    drawer.shotLink = h('a', { class: 'shot-large', attrs: { target: '_blank', rel: 'noopener' } }, drawer.shot, drawer.shotNone);
    drawer.facts = h('dl', 'facts');
    drawer.timeline = h('ol', 'timeline');
    drawer.el = h('div', { class: 'drawer', hidden: true },
      h('div', { class: 'drawer-backdrop', on: { click: closeAgent } }),
      h('section', { class: 'drawer-panel', attrs: { role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'drawerName' } },
        h('header', 'drawer-head', drawer.avatar, h('div', 'drawer-title', drawer.name, h('div', 'drawer-sub', drawer.id, drawer.role, drawer.state)), drawer.close),
        h('div', 'drawer-body', drawer.shotLink, drawer.facts, h('h3', { text: 'Son hərəkətlər' }), drawer.timeline)));
    document.body.append(drawer.el);
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeAgent(); });
  }

  function fact(label, value, cls) {
    drawer.facts.append(h('dt', { text: label }));
    const dd = h('dd', { class: cls || '', text: value });
    drawer.facts.append(dd);
    return dd;
  }

  function renderDrawer(a, timeline) {
    drawer.name.textContent = a.name;
    drawer.id.textContent = a.id;
    drawer.avatar.textContent = fmt.initials(a.name);
    drawer.avatar.style.setProperty('--h', fmt.hue(a.name));
    drawer.role.textContent = L.role[a.role] || 'Rol məlum deyil';
    drawer.role.className = 'role role-' + (a.role || 'none');
    drawer.state.textContent = L.agentState[a.state] || a.state;
    drawer.state.className = 'state-tag st-' + a.state;
    if (a.screenshot) {
      const url = artifactUrl(a.screenshot);
      if (drawer.shot.getAttribute('src') !== url) drawer.shot.src = url;
      drawer.shotLink.href = url;
    } else {
      drawer.shot.removeAttribute('src');
      drawer.shotLink.removeAttribute('href');
    }
    drawer.shot.hidden = !a.screenshot;
    drawer.shotNone.hidden = !!a.screenshot;
    drawer.facts.textContent = '';
    fact('Cari addım', a.step || '—', 'mono');
    fact('Şöbə', a.department || (a.role === 'admin' ? 'Rəhbərlik' : '—'));
    fact('Qeydiyyat', L.registration[a.registration] || '—');
    fact('Son əməliyyat', a.lastAction || '—', 'mono');
    drawer.when = fact('Nə vaxt', a.lastActionAtMs ? fmt.clock(a.lastActionAtMs) + ' · ' + fmt.ago(a.lastActionAtMs) : '—');
    fact('Əməliyyatlar', String(a.actions));
    fact('Xətalar', String(a.failures), a.failures ? 'fail' : null);
    if (a.lastFailure) fact('Son xəta', a.lastFailure, 'fail');
    if (timeline) {
      P.fill(drawer.timeline, timeline.length ? timeline.map((e) => entryEl(e, false, false)) : h('li', { class: 'empty-inline', text: 'Hələ qeyd yoxdur.' }));
    }
  }

  async function fetchDetail() {
    const id = selected;
    if (!id) return;
    const res = await P.api.get('/api/agents/' + encodeURIComponent(id));
    if (res.ok && selected === id) renderDrawer(res.data.agent, res.data.timeline);
  }

  function openAgent(id) {
    const c = cards.get(id);
    if (!c) return;
    lastFocus = document.activeElement;
    selected = id;
    detailSig = c.sig;
    renderDrawer(c.data, null);
    drawer.el.hidden = false;
    document.body.classList.add('no-scroll');
    drawer.close.focus();
    fetchDetail();
  }

  function closeAgent() {
    if (!selected) return;
    selected = null;
    drawer.el.hidden = true;
    document.body.classList.remove('no-scroll');
    if (lastFocus && lastFocus.focus) lastFocus.focus();
  }

  // ---------- render ----------
  function render(s) {
    if (snap && s.version === snap.version && s.run.runId === snap.run.runId) return;
    snap = s;
    ui.banner.update({ version: s.version, generatedAtMs: s.generatedAtMs, run: s.run, report: s.report, counters: s.counters });
    renderTiles(s);
    renderAgents(s);
    renderTimeline(s);
    renderFindings(s);
    if (selected) {
      const c = cards.get(selected);
      if (!c) closeAgent();
      else if (c.sig !== detailSig) {
        detailSig = c.sig;
        if (!detailTimer) detailTimer = setTimeout(() => { detailTimer = null; fetchDetail(); }, 600);
      }
    }
    document.body.dataset.version = String(s.version);
  }

  function tile(label, ...content) { return h('div', 'tile', h('span', { class: 'tile-label', text: label }), ...content); }
  function pairItem(cls, sign) { const value = h('b', { text: '0' }); const box = h('span', cls, sign + ' ', value); return { box, value }; }

  function mount(el) {
    ui = {};
    ui.banner = P.runBanner();
    ui.agentsTotal = h('span', { class: 'tile-big', text: '0' });
    ui.stateBar = h('div', 'state-bar');
    ui.legend = h('div', 'state-legend');
    for (const st of L.agentStates) {
      ui.stateBar.append(h('i', { title: L.agentState[st], style: { background: 'var(' + L.stateVar[st] + ')', width: '0' } }));
      ui.legend.append(h('div', 'legend-item', h('span', { class: 'sw', style: { '--c': 'var(' + L.stateVar[st] + ')' } }), h('span', { text: L.agentState[st] }), h('b', { text: '0' })));
    }
    ui.stepsOk = pairItem('ok', '✓'); ui.stepsBad = pairItem('bad', '✗');
    ui.asOk = pairItem('ok', '✓'); ui.asBad = pairItem('bad', '✗');
    ui.asSkip = h('b', { text: '0' });
    ui.events = h('span', { class: 'tile-big', text: '0' });
    ui.rcOk = pairItem('ok', '✓'); ui.rcBad = pairItem('bad', '✗');
    ui.rcBar = h('i', { style: { width: '0', '--c': 'var(--green)' } });
    ui.findings = h('span', { class: 'tile-big', text: '0' });
    ui.findTile = tile('Tapıntılar', ui.findings, h('div', { class: 'tile-foot', text: 'üç mənbəli müqayisə' }));
    const tiles = h('section', { class: 'tiles', attrs: { 'aria-label': 'Sayğaclar' } },
      h('div', 'tile tile-agents', h('div', 'tile-head', h('span', { class: 'tile-label', text: 'Agentlər' }), ui.agentsTotal), ui.stateBar, ui.legend),
      tile('Addımlar', h('div', 'pair', ui.stepsOk.box, ui.stepsBad.box), h('div', { class: 'tile-foot', text: 'keçdi / uğursuz' })),
      tile('Təsdiqlər', h('div', 'pair', ui.asOk.box, ui.asBad.box, h('span', null, '⤼ ', ui.asSkip)), h('div', { class: 'tile-foot', text: 'keçdi / uğursuz / buraxıldı' })),
      tile('Hadisələr', ui.events, h('div', { class: 'tile-foot', text: 'yayımlanan hadisə' })),
      tile('Qəbzlər', h('div', 'pair', ui.rcOk.box, ui.rcBad.box), h('div', 'progress thin', ui.rcBar)),
      ui.findTile);

    const search = h('input', { class: 'input', attrs: { type: 'search', placeholder: 'Ad, ID, şöbə və ya addım…', autocomplete: 'off', spellcheck: 'false', 'aria-label': 'Agent axtar' } });
    search.addEventListener('input', () => { filters.q = search.value.trim().toLocaleLowerCase('az'); applyFilters(); });
    ui.search = search;
    const stateChips = h('div', { class: 'chips', attrs: { role: 'group', 'aria-label': 'Vəziyyət filtri' } });
    ui.stateChips = {};
    for (const st of L.agentStates) { const c = chip(L.agentState[st], st, L.stateVar[st], filters.states); ui.stateChips[st] = c; stateChips.append(c.b); }
    const roleChips = h('div', { class: 'chips', attrs: { role: 'group', 'aria-label': 'Rol filtri' } });
    ui.roleChips = {};
    for (const r of ['admin', 'manager', 'employee']) { const c = chip(L.role[r], r, null, filters.roles); ui.roleChips[r] = c; roleChips.append(c.b); }
    ui.densityCards = h('button', { attrs: { type: 'button', title: 'Böyük kartlar', 'aria-label': 'Böyük kartlar' }, on: { click: () => setDensity('cards') } }, P.icon('grid', 'sm'));
    ui.densityCompact = h('button', { attrs: { type: 'button', title: 'Sıx siyahı', 'aria-label': 'Sıx siyahı' }, on: { click: () => setDensity('compact') } }, P.icon('list', 'sm'));
    ui.shown = h('span', 'shown');
    ui.grid = h('div', { class: 'agent-grid', attrs: { id: 'agentGrid' } });
    ui.emptyTitle = h('strong', { text: 'Run gözlənilir' });
    ui.emptyText = h('p');
    ui.empty = h('div', 'empty-state', P.icon('honeycomb', 'art'), h('div', null, ui.emptyTitle, ui.emptyText));

    ui.tabTimeline = h('button', { class: 'tab', text: 'Axın', attrs: { type: 'button', role: 'tab', 'aria-selected': 'true' }, on: { click: () => selectTab('timeline') } });
    ui.findBadge = h('span', { class: 'count', text: '0' });
    ui.tabFindings = h('button', { class: 'tab', attrs: { type: 'button', role: 'tab', 'aria-selected': 'false' }, on: { click: () => selectTab('findings') } }, 'Tapıntılar ', ui.findBadge);
    const onlyFail = h('input', { attrs: { type: 'checkbox' } });
    onlyFail.addEventListener('change', () => ui.timeline.classList.toggle('only-fail-on', onlyFail.checked));
    ui.onlyFailBox = h('label', 'only-fail', onlyFail, h('span', { text: 'Yalnız xətalar' }));
    ui.timeline = h('ol', 'timeline');
    ui.timelineEmpty = h('div', { class: 'empty-inline', text: 'Hələ heç nə baş verməyib.' });
    ui.timelinePane = h('div', 'scroll', ui.timeline, ui.timelineEmpty);
    ui.findingsList = h('div', 'findings');
    ui.findingsEmpty = h('div', { class: 'empty-inline', text: 'Tapıntı yoxdur.' });
    ui.findingsPane = h('div', { class: 'scroll', hidden: true }, ui.findingsList, ui.findingsEmpty);

    P.append(el,
      ui.banner.el,
      tiles,
      h('div', 'board-layout',
        h('section', { class: 'card', attrs: { 'aria-label': 'Agentlər' } },
          h('div', 'toolbar',
            h('label', 'search', P.icon('search', 'sm'), search),
            stateChips, h('span', { class: 'vsep', attrs: { 'aria-hidden': 'true' } }), roleChips,
            ui.shown,
            h('div', { class: 'segmented', attrs: { role: 'group', 'aria-label': 'Görünüş sıxlığı' } }, ui.densityCards, ui.densityCompact)),
          ui.grid, ui.empty),
        h('aside', { class: 'card side-panel', attrs: { 'aria-label': 'Axın və tapıntılar' } },
          h('div', { class: 'tabs', attrs: { role: 'tablist' } }, ui.tabTimeline, ui.tabFindings, ui.onlyFailBox),
          ui.timelinePane, ui.findingsPane)));
    buildDrawer();
    applyDensity();
    applyFilters();
    P.stream.on('snapshot', (s) => { if (visible) render(s); });
    P.onTick(() => {
      if (!visible) return;
      for (const c of cards.values()) if (c.inView && !c.el.hidden && c.data) c.ago.textContent = fmt.ago(c.data.lastActionAtMs);
      if (selected && drawer.when) {
        const c = cards.get(selected);
        if (c && c.data.lastActionAtMs) drawer.when.textContent = fmt.clock(c.data.lastActionAtMs) + ' · ' + fmt.ago(c.data.lastActionAtMs);
      }
    });
    document.addEventListener('keydown', (e) => {
      if (visible && e.key === '/' && document.activeElement === document.body) { e.preventDefault(); search.focus(); }
    });
  }

  P.register({
    id: 'agentler',
    title: 'Agentlər',
    subtitle: 'Kim nə edir — canlı: kartlar, screenshot-lar, axın və tapıntılar',
    icon: 'agents',
    group: 'İcra',
    topics: ['board', 'jobs'],
    mount,
    show() { visible = true; snap = null; },
    hide() { visible = false; closeAgent(); },
    /** Sidebar badge: how many agents are working now. */
    badge(header) {
      if (!header || !header.run || header.run.phase !== 'RUNNING') return null;
      const k = header.counters;
      return { text: String((k.agentsByState.WORKING || 0) + (k.agentsByState.WAITING || 0)), tone: 'live', title: 'işləyən / gözləyən agent' };
    },
  });
})();
