/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

/* Ssenarilər — versioned scenarios: status, YAML, diff between versions, approve / freeze, and the triage of runs. */
(() => {
  'use strict';
  const P = window.Petek;
  const { h, L, fmt } = P;
  const artifactUrl = (id) => '/artifacts/' + encodeURIComponent(id);

  let ui = null;
  let versions = [];
  let selectedId = null;
  let tab = 'yaml';
  let runs = [];
  let triageRun = null;

  function statusBadge(status) { const b = P.badge('slate', L.scenarioStatus[status] || status, { dot: true }); b.classList.add('ss-' + status); return b; }
  function sourceTag(source) { return P.tag('src-' + source, L.scenarioSource[source] || source); }

  // ---------- list ----------
  function renderList() {
    if (!versions.length) {
      P.fill(ui.list, h('div', { class: 'empty-inline', text: 'Hələ ssenari yoxdur. Kəşfiyyatdan layihə yaradın və ya kampaniya faylı idxal edin.' }));
      return;
    }
    const groups = new Map();
    for (const v of versions) { if (!groups.has(v.name)) groups.set(v.name, []); groups.get(v.name).push(v); }
    P.fill(ui.list, [...groups.entries()].map(([name, list]) => h('div', 'scn-group',
      h('div', 'scn-group-title', P.icon('layers', 'sm'), h('span', { class: 'ellipsis', text: name }), h('span', 'spacer'), h('span', { class: 'count', text: String(list.length) })),
      list.sort((a, b) => b.version - a.version).map((v) => h('button', {
        class: 'scn-version', attrs: { type: 'button', 'aria-selected': String(v.id === selectedId) }, data: { id: v.id },
        on: { click: () => P.go('ssenariler', { id: v.id }) },
      }, h('span', { class: 'v', text: 'v' + v.version }), statusBadge(v.status), sourceTag(v.source), h('span', { class: 'when', text: fmt.date(v.createdAtMs) }))))));
  }

  // ---------- detail ----------
  async function renderDetail() {
    const meta = versions.find((v) => v.id === selectedId);
    if (!meta) {
      P.fill(ui.detail, P.empty('Ssenari seçin', 'Soldakı siyahıdan bir versiya seçin: YAML-ı, əvvəlki versiya ilə fərqi və run planını görəcəksiniz.'));
      return;
    }
    const res = await P.api.get('/api/scenarios/' + encodeURIComponent(meta.id));
    if (meta.id !== selectedId) return;
    if (!res.ok) { P.fill(ui.detail, h('div', { class: 'empty-inline', text: res.error })); return; }
    const scn = res.data;
    const v = scn.version;
    const parent = v.parentId ? versions.find((x) => x.id === v.parentId) : null;
    const facts = h('div', 'run-meta',
      h('span', null, P.icon('clock', 'sm'), 'yaradılıb ' + fmt.date(v.createdAtMs)),
      v.approvedAtMs ? h('span', null, P.icon('checkCircle', 'sm'), 'təsdiq ' + fmt.date(v.approvedAtMs)) : null,
      v.frozenAtMs ? h('span', null, P.icon('lock', 'sm'), 'dondurulub ' + fmt.date(v.frozenAtMs)) : null,
      parent ? h('span', null, P.icon('compare', 'sm'), 'v' + parent.version + '-dən törəyib') : null,
      h('span', { class: 'mono', text: v.id }));
    const actions = h('div', 'row wrap');
    if (v.status === 'DRAFT') actions.append(P.button('Təsdiqlə', { kind: 'primary small', icon: 'check', on: (e) => transition(e.currentTarget, v, 'approve', 'Təsdiqləndi') }));
    if (v.status === 'APPROVED') actions.append(P.button('Dondur', { kind: 'small', icon: 'lock', on: (e) => transition(e.currentTarget, v, 'freeze', 'Donduruldu') }));
    if (v.runnable) actions.append(P.button('Run et', { kind: 'dark small', icon: 'play', on: (e) => run(e.currentTarget, v) }));
    const tabs = h('div', { class: 'segmented', attrs: { role: 'tablist' } },
      ['yaml', 'diff', 'plan'].map((t) => h('button', {
        text: { yaml: 'YAML', diff: 'Fərq', plan: 'Plan' }[t], attrs: { type: 'button', role: 'tab', 'aria-selected': String(tab === t) },
        on: { click: () => { tab = t; renderDetail(); } },
      })));
    const body = h('div', 'card-body');
    const card = h('section', { class: 'card', attrs: { 'aria-label': v.name } },
      h('div', 'card-head',
        h('div', { style: { 'min-width': '0', flex: '1' } },
          h('div', 'row wrap', h('h2', { text: v.name }), h('span', { class: 'mono faint', text: 'v' + v.version }), statusBadge(v.status), sourceTag(v.source)),
          facts),
        actions),
      v.note ? h('div', { class: 'card-body help', style: { 'border-bottom': '1px solid var(--border)' } }, v.note) : null,
      h('div', { class: 'card-body', style: { 'padding-bottom': '0' } }, tabs),
      body);
    P.fill(ui.detail, card);
    if (tab === 'yaml') body.append(P.code(scn.yaml));
    else if (tab === 'diff') renderDiff(body, v, parent);
    else renderPlan(body, v);
  }

  async function renderDiff(body, v, parent) {
    const others = versions.filter((x) => x.name === v.name && x.id !== v.id).sort((a, b) => b.version - a.version);
    if (!others.length) { body.append(h('div', { class: 'empty-inline', text: 'Müqayisə üçün başqa versiya yoxdur.' })); return; }
    const fallback = parent || others.find((x) => x.version < v.version) || others[0];
    const select = h('select', { class: 'select', attrs: { 'aria-label': 'Müqayisə olunan versiya' } },
      others.map((x) => h('option', { text: 'v' + x.version + ' · ' + L.scenarioStatus[x.status], attrs: { value: x.id } })));
    select.value = fallback.id;
    const stats = h('span', 'help');
    const out = h('div');
    const load = async () => {
      const res = await P.api.get('/api/scenarios/' + encodeURIComponent(v.id) + '/diff?from=' + encodeURIComponent(select.value));
      if (!res.ok) { P.fill(out, h('div', { class: 'empty-inline', text: res.error })); return; }
      const d = res.data;
      stats.textContent = 'v' + d.from.version + ' → v' + d.to.version + ' · +' + d.added + ' −' + d.removed;
      P.fill(out, d.lines.length
        ? h('pre', 'diff', d.lines.map((l) => h('span', 'dl ' + l.kind,
          h('span', { class: 'no', text: l.old === null ? '' : String(l.old) }),
          h('span', { class: 'no', text: l.new === null ? '' : String(l.new) }),
          h('span', { class: 'sg', text: l.kind === 'ADDED' ? '+' : l.kind === 'REMOVED' ? '−' : ' ' }),
          h('span', { text: l.text }))))
        : h('div', { class: 'empty-inline', text: 'Fərq yoxdur.' }));
    };
    select.addEventListener('change', load);
    body.append(h('div', 'stack', h('div', 'row wrap', h('span', { class: 'kicker', text: 'Müqayisə' }), h('div', { style: { width: '220px' } }, select), stats), out));
    load();
  }

  async function renderPlan(body, v) {
    const res = await P.api.get('/api/scenarios/' + encodeURIComponent(v.id) + '/plan');
    if (!res.ok) { body.append(h('div', { class: 'empty-inline', text: res.error })); return; }
    const plan = res.data;
    body.append(h('div', 'lanes', plan.steps.map((s, i) => h('div', 'lane' + (s.setup ? ' setup' : ''),
      h('div', 'idx', h('span', { text: String(i + 1) })),
      h('div', 'info',
        h('span', { class: 'step-id', text: s.id }),
        h('div', { class: 'step-action', text: s.action }),
        h('div', { class: 'help', text: s.actors + ' · ' + s.agentIds.length + ' agent' })),
      h('div', 'cells-wrap',
        h('div', 'flow-tags',
          s.setup ? P.tag('tone-cyan', 'hazırlıq') : null,
          P.tag(s.kind === 'run' ? 'tone-slate' : 'tone-brand', s.kind === 'run' ? 'run · kod' : 'do · LLM'),
          s.parallel ? P.tag('tone-orange', 'paralel start') : null,
          s.emits ? P.tag('tone-green', '⇢ yayır: ' + s.emits) : null,
          s.waitFor ? P.tag('tone-amber', '⇠ gözləyir: ' + s.waitFor) : null),
        s.assertions.length ? h('ul', { class: 'help', style: { margin: '0', 'padding-left': '18px' } }, s.assertions.map((a) => h('li', { class: 'mono', text: a }))) : null)))));
  }

  async function transition(button, v, action, done) {
    const res = await P.busy(button, () => P.api.post('/api/scenarios/' + encodeURIComponent(v.id) + '/' + action));
    if (!res.ok) { P.toast(res.error, 'error'); return; }
    P.toast(done + ': ' + v.name + ' v' + v.version, 'ok');
    await reload();
  }

  async function run(button, v) {
    const res = await P.busy(button, () => P.api.post('/api/runs', { scenarioId: v.id, testers: null, headful: false }));
    if (!res.ok) { P.toast(res.error, 'error'); return; }
    P.toast('Run başladı: ' + res.data.runId, 'ok');
    P.go('agentler');
  }

  // ---------- triage ----------
  async function renderTriage() {
    const finished = runs.filter((r) => r.result !== 'RUNNING');
    if (!finished.length) {
      P.fill(ui.triageRuns, h('option', { text: 'Bitmiş run yoxdur', attrs: { value: '' } }));
      P.fill(ui.triage, h('div', { class: 'empty-inline', text: 'Run bitəndən sonra onun sürprizləri (agentin bildirdiyi problemlər, uğursuz addımlar, tapıntılar) burada təsnif olunacaq.' }));
      ui.triageBtn.hidden = true;
      return;
    }
    if (!triageRun || !finished.some((r) => r.runId === triageRun)) triageRun = (finished.find((r) => r.triaged) || finished[0]).runId;
    P.fill(ui.triageRuns, finished.map((r) => h('option', { text: fmt.date(r.startedAtMs) + ' · ' + r.campaignName + (r.triaged ? ' · triaj var' : ''), attrs: { value: r.runId } })));
    ui.triageRuns.value = triageRun;
    const res = await P.api.get('/api/runs/' + encodeURIComponent(triageRun) + '/triage');
    const t = res.ok ? res.data : null;
    ui.triageBtn.hidden = !!t;
    if (!t) { P.fill(ui.triage, h('div', { class: 'empty-inline', text: res.ok ? 'Bu run hələ triaj olunmayıb. "Triaj et" hər sürprizi təsnif edəcək.' : res.error })); return; }
    if (!t.verdicts.length) { P.fill(ui.triage, h('div', { class: 'empty-inline', text: 'Bu run-da sürpriz yoxdur.' })); return; }
    P.fill(ui.triage, h('div', 'verdicts', t.verdicts.map((v) => {
      const cat = P.badge('slate', L.triage[v.category] || v.category, { dot: true });
      cat.classList.add('tc-' + v.category);
      const proposal = v.proposalScenarioId ? versions.find((x) => x.id === v.proposalScenarioId) : null;
      return h('article', 'verdict tc-' + v.category,
        h('div', 'row wrap', cat, h('span', 'spacer'), h('span', { class: 'entry-step', text: v.step }), v.agentId ? h('span', { class: 'agent-link', text: v.agentId }) : null),
        h('div', { class: 'help', text: L.triageHelp[v.category] || '' }),
        h('div', 'surprise', h('div', { class: 'kicker', text: L.surprise[v.surpriseKind] || v.surpriseKind }), v.surprise),
        h('div', { class: 'why', text: v.rationale }),
        h('div', 'confidence', h('span', { text: 'Əminlik' }), h('div', 'progress thin', h('i', { style: { width: Math.round(v.confidence * 100) + '%' } })), h('b', { text: Math.round(v.confidence * 100) + '%' })),
        v.proposedChange ? h('div', 'stack', h('span', { class: 'kicker', text: 'Təklif olunan dəyişiklik' }), h('div', { class: 'proposal', text: v.proposedChange })) : null,
        h('div', 'row wrap',
          proposal ? P.button('v' + proposal.version + ' təklifinə bax', { kind: 'small', icon: 'compare', on: () => { tab = 'diff'; P.go('ssenariler', { id: proposal.id, run: triageRun }); } }) : null,
          h('span', 'spacer'),
          v.evidence.length ? h('div', 'proofs', v.evidence.map((id, i) => h('a', { text: 'Sübut ' + (i + 1) + ' ↗', attrs: { href: artifactUrl(id), target: '_blank', rel: 'noopener' } }))) : null));
    })));
  }

  async function startTriage(button) {
    if (!triageRun) return;
    const res = await P.busy(button, () => P.api.post('/api/runs/' + encodeURIComponent(triageRun) + '/triage'));
    if (!res.ok) { P.toast(res.error, 'error'); return; }
    P.toast('Triaj hazırdır.', 'ok');
    await reload();
  }

  async function reload() {
    const [list, history] = await Promise.all([P.api.get('/api/scenarios'), P.api.get('/api/runs')]);
    versions = list.ok ? list.data : [];
    runs = history.ok ? history.data : [];
    if (!selectedId || !versions.some((v) => v.id === selectedId)) {
      const approved = versions.find((v) => v.status === 'APPROVED') || versions[0];
      selectedId = approved ? approved.id : null;
    }
    ui.count.textContent = versions.length ? versions.length + ' versiya' : '';
    renderList();
    await Promise.all([renderDetail(), renderTriage()]);
  }

  function mount(el) {
    ui = {};
    const list = P.card('Versiyalar', { icon: 'layers', flush: true });
    ui.count = h('span', 'help');
    list.actions.append(ui.count, h('button', { class: 'icon-btn', attrs: { type: 'button', 'aria-label': 'Yenilə', title: 'Yenilə' }, on: { click: reload } }, P.icon('refresh', 'sm')));
    ui.list = h('div', 'scn-list');
    list.body.append(ui.list);
    ui.detail = h('div', 'stack');

    const triage = P.card('Triaj', { icon: 'flag', flush: true, sub: 'Run-ın sürprizləri: sistem xətası, model boşluğu və ya ssenari xətası' });
    ui.triageRuns = h('select', { class: 'select', attrs: { 'aria-label': 'Run' } });
    ui.triageRuns.addEventListener('change', () => { triageRun = ui.triageRuns.value; renderTriage(); });
    ui.triageBtn = P.button('Triaj et', { kind: 'small primary', icon: 'sparkles', on: (e) => startTriage(e.currentTarget) });
    triage.actions.append(h('div', { style: { width: '320px', 'max-width': '100%' } }, ui.triageRuns), ui.triageBtn);
    ui.triage = h('div');
    triage.body.append(ui.triage);

    P.append(el, h('div', 'scn-layout', list.el, ui.detail), triage.el);
  }

  P.register({
    id: 'ssenariler',
    title: 'Ssenarilər',
    subtitle: 'Versiyalar, təsdiq və dondurma, fərqlər və run-ların triajı',
    icon: 'scenarios',
    group: 'Hazırlıq',
    topics: ['run', 'jobs'],
    mount,
    show(params) {
      if (params.id) selectedId = params.id;
      if (params.run) triageRun = params.run;
      reload();
    },
  });
})();
