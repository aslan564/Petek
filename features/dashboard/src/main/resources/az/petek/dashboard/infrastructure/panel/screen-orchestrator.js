/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

/* Orkestrator — the run plan as ordered step lanes, the live task matrix (steps × agents) and the event timeline. */
(() => {
  'use strict';
  const P = window.Petek;
  const { h, L, fmt } = P;

  let ui = null;
  let visible = false;
  let planSig = '';
  let eventsSig = '';
  const cellsByKey = new Map();
  const lanesById = new Map();
  let agentsById = new Map();
  let arrows = null;

  function planSignature(plan) {
    return plan ? JSON.stringify([plan.runId, plan.steps.map((s) => [s.id, s.agentIds.join(','), s.emits, s.waitFor])]) : '';
  }

  function stepTags(step) {
    const tags = h('div', 'flow-tags');
    if (step.setup) tags.append(P.tag('tone-cyan', 'hazırlıq'));
    tags.append(P.tag(step.kind === 'run' ? 'tone-slate' : 'tone-brand', step.kind === 'run' ? 'run · kod' : 'do · LLM'));
    if (step.parallel) tags.append(P.tag('tone-orange', 'paralel start'));
    if (step.emits) tags.append(P.tag('tone-green', '⇢ yayır: ' + step.emits, 'Bu addım hadisə yayır'));
    if (step.waitFor) tags.append(P.tag('tone-amber', '⇠ gözləyir: ' + step.waitFor, 'Bu addım hadisəni gözləyir'));
    if (step.assertions.length) tags.append(P.tag('tone-violet', step.assertions.length + ' yoxlama', step.assertions.join('\n')));
    return tags;
  }

  function buildLanes(plan) {
    cellsByKey.clear();
    lanesById.clear();
    const lanes = h('div', 'lanes');
    plan.steps.forEach((step, i) => {
      const cells = h('div', { class: 'cells', attrs: { role: 'list', 'aria-label': step.id + ' tapşırıqları' } });
      for (const id of step.agentIds) {
        const cell = h('span', { class: 'cell ts-PENDING', attrs: { role: 'listitem' } });
        cellsByKey.set(step.id + '|' + id, { cell, agentId: id, state: 'PENDING', detail: null });
        cells.append(cell);
      }
      const sum = h('div', 'lane-sum');
      const lane = h('div', { class: 'lane' + (step.setup ? ' setup' : ''), data: { step: step.id } },
        h('div', 'idx', h('span', { text: String(i + 1) })),
        h('div', 'info',
          h('div', 'row wrap', h('span', { class: 'step-id', text: step.id })),
          h('div', { class: 'step-action', text: step.action, title: step.action }),
          h('div', { class: 'help', text: step.actors + ' · ' + step.agentIds.length + ' agent' }),
          stepTags(step)),
        h('div', 'cells-wrap', cells, sum));
      lanesById.set(step.id, { lane, sum, step });
      lanes.append(lane);
    });
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'lane-arrows');
    svg.setAttribute('aria-hidden', 'true');
    lanes.append(svg);
    P.fill(ui.lanes, lanes);
    arrows = { plan, lanes, svg };
    requestAnimationFrame(redrawArrows);
  }

  function redrawArrows() { if (arrows && visible) drawArrows(arrows.plan, arrows.lanes, arrows.svg); }

  /** Dashed arrows in the left gutter from each step that emits an event to every later step that waits for it. */
  function drawArrows(plan, lanes, svg) {
    const NS = 'http://www.w3.org/2000/svg';
    svg.textContent = '';
    const top = lanes.getBoundingClientRect().top;
    const centre = (id) => {
      const entry = lanesById.get(id);
      if (!entry) return null;
      const dot = entry.lane.querySelector('.idx span').getBoundingClientRect();
      return dot.top - top + dot.height / 2;
    };
    svg.setAttribute('height', String(lanes.scrollHeight));
    let bend = 0;
    plan.steps.forEach((emitter, i) => {
      if (!emitter.emits) return;
      plan.steps.slice(i + 1).filter((s) => s.waitFor === emitter.emits).forEach((waiter) => {
        const y1 = centre(emitter.id);
        const y2 = centre(waiter.id);
        if (y1 === null || y2 === null) return;
        const x = 3 + (bend++ % 3) * 2;
        const path = document.createElementNS(NS, 'path');
        path.setAttribute('d', 'M 9 ' + y1 + ' C ' + x + ' ' + y1 + ', ' + x + ' ' + y2 + ', 8 ' + y2);
        const head = document.createElementNS(NS, 'path');
        head.setAttribute('class', 'head');
        head.setAttribute('d', 'M 9 ' + y2 + ' l -5 -3.5 v 7 z');
        const title = document.createElementNS(NS, 'title');
        title.textContent = emitter.id + ' → ' + waiter.id + ' (' + emitter.emits + ')';
        path.append(title);
        svg.append(path, head);
      });
    });
  }

  function cellTitle(entry) {
    const agent = agentsById.get(entry.agentId);
    return entry.agentId + (agent ? ' · ' + agent.name : '') + ' — ' + (L.taskState[entry.state] || entry.state) + (entry.detail ? '\n' + entry.detail : '');
  }

  function updateCells(data) {
    const seen = new Set();
    for (const t of data.tasks) {
      const key = t.step + '|' + t.agentId;
      const entry = cellsByKey.get(key);
      if (!entry) continue;
      seen.add(key);
      if (entry.state !== t.state || entry.detail !== t.detail) {
        entry.state = t.state;
        entry.detail = t.detail;
        entry.cell.className = 'cell ts-' + t.state;
      }
    }
    for (const [key, entry] of cellsByKey) {
      if (!seen.has(key) && entry.state !== 'PENDING') { entry.state = 'PENDING'; entry.detail = null; entry.cell.className = 'cell ts-PENDING'; }
      entry.cell.title = cellTitle(entry);
    }
    for (const { sum, step } of lanesById.values()) {
      const counts = {};
      for (const id of step.agentIds) { const s = cellsByKey.get(step.id + '|' + id).state; counts[s] = (counts[s] || 0) + 1; }
      P.fill(sum, L.taskStates.filter((s) => counts[s]).map((s) =>
        h('span', null, h('i', { class: 'sw ts-' + s }), (L.taskState[s] || s) + ' ' + counts[s])));
    }
  }

  function renderCounts(data) {
    const total = Object.values(data.taskCounts).reduce((a, b) => a + b, 0);
    P.fill(ui.counts, L.taskStates.map((s) => {
      const n = data.taskCounts[s] || 0;
      const b = P.badge('slate', (L.taskState[s] || s) + ' · ' + n, { dot: true, live: s === 'RUNNING' && n > 0 });
      b.classList.add('ts-' + s);
      if (!n) b.style.setProperty('opacity', '.5');
      return b;
    }));
    const done = (data.taskCounts.PASSED || 0) + (data.taskCounts.FAILED || 0) + (data.taskCounts.SKIPPED || 0) + (data.taskCounts.LOST_RACE || 0) + (data.taskCounts.BLOCKED || 0);
    ui.progress.style.setProperty('width', (total ? (100 * done) / total : 0) + '%');
    ui.progressText.textContent = total ? fmt.int(done) + ' / ' + fmt.int(total) + ' tapşırıq bitib' : 'Plan gözlənilir';
  }

  function latencyClass(r) {
    if (!r.received) return 'miss';
    if (r.latencyMs === null || r.latencyMs < 1000) return 'fast';
    return r.latencyMs < 3000 ? 'slow' : 'late';
  }

  function renderEvents(data) {
    const sig = JSON.stringify(data.events.map((e) => [e.id, e.received, e.missing]));
    if (sig === eventsSig) return;
    eventsSig = sig;
    ui.eventCount.textContent = data.events.length ? data.events.length + ' hadisə' : '';
    if (!data.events.length) {
      P.fill(ui.events, h('div', { class: 'empty-inline', text: 'Hələ hadisə yayımlanmayıb. Addım "emits" edəndə burada t0 və alıcıların gecikməsi görünəcək.' }));
      return;
    }
    P.fill(ui.events, data.events.map((e) => {
      const total = e.received + e.missing;
      const emitter = agentsById.get(e.emitter);
      const missing = e.receipts.filter((r) => !r.received).map((r) => r.agentId);
      return h('div', 'event-row',
        h('div', null,
          h('div', { class: 'event-name', text: e.name }),
          h('div', 'event-meta',
            h('span', { class: 'agent-link', text: e.emitter, title: emitter ? emitter.name : e.emitter }),
            h('span', { text: 't0 ' + fmt.clock(e.t0Ms) }),
            e.objectId ? h('span', { class: 'mono', text: '#' + e.objectId }) : null)),
        h('div', 'receivers-cell',
          total
            ? h('div', 'receivers', e.receipts.map((r) => h('span', { class: 'rcv ' + latencyClass(r), title: r.agentId + (r.received ? ' · ' + fmt.latency(r.latencyMs) : ' · görmədi') })))
            : h('span', { class: 'help', text: 'Alıcı qəbzi hələ yoxdur' }),
          missing.length ? h('div', { class: 'missing-list', text: 'Görmədi: ' + missing.join(', ') }) : null),
        h('div', 'event-stats',
          h('div', null, h('b', { text: e.received + ' / ' + total }), ' gördü'),
          h('div', { text: 'median ' + fmt.latency(e.medianMs) + ' · maks ' + fmt.latency(e.maxMs) })));
    }));
  }

  function render(data) {
    agentsById = new Map(data.agents.map((a) => [a.id, a]));
    const sig = planSignature(data.plan);
    if (sig !== planSig) {
      planSig = sig;
      if (data.plan) {
        ui.planTitle.textContent = data.plan.campaignName + ' · ' + data.plan.steps.length + ' addım';
        buildLanes(data.plan);
      } else {
        ui.planTitle.textContent = 'Plan hələ yoxdur';
        cellsByKey.clear();
        lanesById.clear();
        arrows = null;
        P.fill(ui.lanes, P.empty('Run planı gözlənilir', 'Run başlayanda orkestrator addımları, aktorları və hadisə əlaqələrini burada göstərəcək; hər xana bir agentin tapşırığıdır.'));
      }
    }
    renderCounts(data);
    updateCells(data);
    renderEvents(data);
  }

  function legend() {
    return h('div', 'legend', L.taskStates.map((s) => h('span', null, h('i', { class: 'cell ts-' + s, style: { width: '12px', height: '12px' } }), L.taskState[s])));
  }

  function mount(el) {
    ui = {};
    ui.banner = P.runBanner();
    ui.counts = h('div', 'task-counts');
    ui.progress = h('i', { style: { width: '0', '--c': 'var(--green)' } });
    ui.progressText = h('span', { class: 'help', text: 'Plan gözlənilir' });
    const summary = P.card('Tapşırıqlar', { icon: 'orchestrator', sub: 'Hər agentin hər addımdakı vəziyyəti' });
    P.append(summary.body, h('div', 'stack', ui.counts, h('div', 'row', h('div', { class: 'progress', style: { flex: '1' } }, ui.progress), ui.progressText)));

    const plan = P.card('Run planı', { icon: 'layers', flush: true });
    ui.planTitle = plan.titles.querySelector('h2');
    plan.titles.append(h('div', { class: 'sub', text: 'Addımlar sırası ilə; xanalar agentlərdir, oxlar "yayır → gözləyir" əlaqəsidir' }));
    ui.lanes = h('div');
    plan.body.append(h('div', 'legend-row', legend()), ui.lanes);

    const events = P.card('Hadisələr', { icon: 'zap', flush: true, sub: 'Yayan agent, t0 və hər alıcının gecikməsi' });
    ui.eventCount = h('span', 'help');
    events.actions.append(
      h('div', 'legend',
        h('span', null, h('i', 'rcv fast'), '< 1 s'), h('span', null, h('i', 'rcv slow'), '1–3 s'),
        h('span', null, h('i', 'rcv late'), '> 3 s'), h('span', null, h('i', 'rcv miss'), 'görmədi')),
      ui.eventCount);
    ui.events = h('div', 'events');
    events.body.append(ui.events);

    P.append(el, ui.banner.el, summary.el, plan.el, events.el);
    P.onRun((header) => { if (visible && header) ui.banner.update(header); });
    P.stream.on('orchestrator', (data) => { if (visible) render(data); });
    let resizeTimer = null;
    window.addEventListener('resize', () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(redrawArrows, 150); });
  }

  P.register({
    id: 'orkestrator',
    title: 'Orkestrator',
    subtitle: 'Run planı, canlı tapşırıq matrisi və hadisələrin gecikməsi',
    icon: 'orchestrator',
    group: 'İcra',
    topics: ['run', 'jobs', 'orchestrator'],
    mount,
    show() {
      visible = true;
      planSig = '';
      eventsSig = '';
      if (P.run.header) ui.banner.update(P.run.header);
    },
    hide() { visible = false; },
  });
})();
