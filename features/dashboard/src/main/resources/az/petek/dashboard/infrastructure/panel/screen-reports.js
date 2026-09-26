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

/* Hesabatlar — run history with results, cost and reports, and the stability of repeat groups. */
(() => {
  'use strict';
  const P = window.Petek;
  const { h, L, fmt } = P;

  let ui = null;

  function resultBadge(result) { const b = P.badge('slate', L.result[result] || result, { dot: true, live: result === 'RUNNING' }); b.classList.add('result-' + result); return b; }

  /** A cell whose content stays together when the table is stacked on a phone. */
  function td(label, ...content) { return h('td', { data: { label } }, h('div', 'cell-v', ...content)); }

  function renderRuns(runs) {
    ui.count.textContent = runs.length ? runs.length + ' run' : '';
    if (!runs.length) {
      P.fill(ui.runs, P.empty('Hələ run yoxdur', 'Təlimat bölməsində ssenarini run edin; hər run-ın nəticəsi, xərci və hesabatı burada toplanacaq.'));
      return;
    }
    P.fill(ui.runs, h('div', 'table-wrap', h('table', 'table stack-sm',
      h('thead', null, h('tr', null, ['Tarix', 'Kampaniya', 'Nəticə', 'Müddət', 'Addımlar', 'Yoxlamalar', 'Tapıntı', 'Token / xərc', ''].map((t) => h('th', { text: t })))),
      h('tbody', null, runs.map((r) => h('tr', { data: { run: r.runId } },
        td('Tarix', h('div', { class: 'nowrap', text: fmt.date(r.startedAtMs) }), r.repeatGroup ? h('div', { class: 'run-sub nowrap', text: r.repeatGroup + ' · #' + r.repeatIndex }) : null),
        td('Kampaniya', h('div', { class: 'run-name', text: r.campaignName }), h('div', { class: 'run-sub', text: fmt.host(r.target) + ' · ' + r.testers + ' tester' })),
        td('Nəticə', resultBadge(r.result)),
        td('Müddət', h('span', { class: 'num nowrap', text: r.durationMs === null ? '—' : P.fmt.duration(r.durationMs) })),
        td('Addımlar', h('span', { class: 'num nowrap' }, h('span', { style: { color: 'var(--green)' }, text: '✓ ' + fmt.int(r.stepsPassed) }), '  ', h('span', { style: { color: r.stepsFailed ? 'var(--red)' : 'var(--faint)' }, text: '✗ ' + fmt.int(r.stepsFailed) }))),
        td('Yoxlamalar', h('span', { class: 'num nowrap' }, h('span', { style: { color: 'var(--green)' }, text: '✓ ' + fmt.int(r.assertionsPassed) }), '  ', h('span', { style: { color: r.assertionsFailed ? 'var(--red)' : 'var(--faint)' }, text: '✗ ' + fmt.int(r.assertionsFailed) }))),
        td('Tapıntı', r.findings ? P.tag('tone-red', String(r.findings)) : h('span', { class: 'faint', text: '0' })),
        td('Token / xərc', h('div', { class: 'num nowrap', text: fmt.tokens(r.inputTokens + r.outputTokens) + ' token' }), h('div', { class: 'run-sub', text: r.costUsd === null ? 'plan daxilində' : fmt.usd(r.costUsd) })),
        td('', h('div', 'row-actions',
          r.reportUrl ? h('a', { class: 'btn small', title: 'HTML hesabatı aç', attrs: { href: r.reportUrl, target: '_blank', rel: 'noopener', 'aria-label': 'Hesabatı aç' } }, P.icon('file', 'sm'), h('span', { text: 'Hesabat' })) : null,
          r.result !== 'RUNNING'
            ? h('button', { class: 'btn small' + (r.triaged ? '' : ' ghost'), title: r.triaged ? 'Triaja bax' : 'Triaj et', attrs: { type: 'button', 'aria-label': r.triaged ? 'Triaja bax' : 'Triaj et' }, on: { click: () => P.go('ssenariler', { run: r.runId }) } }, P.icon('flag', 'sm'))
            : null))))))));
  }

  async function renderStability(runs) {
    const groups = [...new Set(runs.filter((r) => r.repeatGroup).map((r) => r.repeatGroup))];
    if (!groups.length) {
      P.fill(ui.stability, h('div', { class: 'empty-inline', text: '"--repeat N" ilə eyni ssenari bir neçə dəfə run olunanda hər addımın sabitliyi burada görünəcək.' }));
      return;
    }
    const results = await Promise.all(groups.map((g) => P.api.get('/api/stability?group=' + encodeURIComponent(g))));
    P.fill(ui.stability, h('div', 'stability', results.filter((r) => r.ok && r.data).map((r) => {
      const s = r.data;
      const flaky = s.steps.filter((x) => x.flaky).length;
      return h('article', 'stab-card',
        h('div', 'row wrap', h('strong', { text: 'Təkrar qrupu ' + s.repeatGroup }), h('span', 'spacer'),
          flaky ? P.badge('amber', flaky + ' qeyri-sabit addım', { dot: true }) : P.badge('green', 'Sabit', { dot: true })),
        h('div', { class: 'help', text: s.runs.length + ' run · ' + s.steps.length + ' addım' }),
        s.steps.map((x) => {
          const rate = x.runs ? x.passed / x.runs : 0;
          const tone = rate === 1 ? 'var(--green)' : rate === 0 ? 'var(--red)' : 'var(--amber)';
          return h('div', 'stab-row' + (x.flaky ? ' flaky' : ''),
            h('span', { class: 'name', text: x.step, title: x.step }),
            h('div', 'progress', h('i', { style: { width: Math.round(rate * 100) + '%', '--c': tone } })),
            h('span', { class: 'rate', text: x.passed + '/' + x.runs }));
        }));
    })));
  }

  async function reload() {
    const res = await P.api.get('/api/runs');
    const runs = res.ok ? res.data : [];
    renderRuns(runs);
    await renderStability(runs);
  }

  function mount(el) {
    ui = {};
    const runs = P.card('Run-lar', { icon: 'reports', flush: true, sub: 'Ən yenisi yuxarıda' });
    ui.count = h('span', 'help');
    runs.actions.append(ui.count, h('button', { class: 'icon-btn', attrs: { type: 'button', 'aria-label': 'Yenilə', title: 'Yenilə' }, on: { click: reload } }, P.icon('refresh', 'sm')));
    ui.runs = h('div');
    runs.body.append(ui.runs);
    const stability = P.card('Sabitlik', { icon: 'compare', flush: true, sub: 'Təkrar run-larda hər addım neçə dəfə keçib' });
    ui.stability = h('div');
    stability.body.append(ui.stability);
    P.append(el, runs.el, stability.el);
    let lastPhase = null;
    P.onRun((header) => {
      const phase = header && header.run ? header.run.runId + ':' + header.run.phase : null;
      if (P.current() && P.current().id === 'hesabatlar' && phase !== lastPhase && lastPhase !== null) reload();
      lastPhase = phase;
    });
  }

  P.register({
    id: 'hesabatlar',
    title: 'Hesabatlar',
    subtitle: 'Run tarixçəsi, HTML hesabatlar, xərc və təkrar run-ların sabitliyi',
    icon: 'reports',
    group: 'Nəticələr',
    topics: ['run', 'jobs'],
    mount,
    show() { reload(); },
  });
})();
