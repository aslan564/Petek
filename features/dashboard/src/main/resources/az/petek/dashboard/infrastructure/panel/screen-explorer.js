/* Kəşfiyyat — the explorer agent live: phases, current page, visited pages, site model, findings, questions, ideas. */
(() => {
  'use strict';
  const P = window.Petek;
  const { h, L, fmt } = P;
  const artifactUrl = (id) => '/artifacts/' + encodeURIComponent(id);
  const PHASE_ICON = { ANONYMOUS: 'globe', ROLE_BASED: 'users', TRIAL_TOUCH: 'send' };
  const STATUS_TONE = { RUNNING: 'blue', FINISHED: 'green', TIMED_OUT: 'amber', FAILED: 'red', CANCELLED: 'slate' };

  let ui = null;
  let visible = false;
  let view = null;
  let receivedAt = 0;
  const sigs = {};
  const drafts = new Map();

  function changed(key, value) {
    const sig = JSON.stringify(value);
    if (sigs[key] === sig) return false;
    sigs[key] = sig;
    return true;
  }

  function thumb(id, alt) {
    const box = h('div', 'thumb');
    if (id) {
      const img = h('img', { attrs: { alt: alt || '', loading: 'lazy', decoding: 'async' } });
      img.addEventListener('load', () => box.classList.add('has-shot'));
      img.src = artifactUrl(id);
      box.append(img);
    }
    box.append(h('span', { class: 'thumb-none', text: 'Screenshot yoxdur' }));
    return box;
  }

  // ---------- header ----------
  function renderHeader(v) {
    const pages = v.phases.reduce((n, p) => n + p.pagesVisited, 0);
    ui.title.textContent = fmt.host(v.target);
    P.fill(ui.meta,
      h('span', null, P.icon('globe', 'sm'), v.target),
      h('span', null, P.icon('clock', 'sm'), 'başladı ' + fmt.clock(v.startedAtMs)),
      h('span', { class: 'mono', text: v.id }));
    P.fill(ui.status, P.badge(STATUS_TONE[v.status] || 'slate', L.explorationStatus[v.status] || v.status, { dot: true, live: v.status === 'RUNNING', lg: true }));
    ui.pages.textContent = pages + ' / ' + v.budget.maxPages;
    ui.pagesBar.style.setProperty('width', Math.min(100, (100 * pages) / Math.max(1, v.budget.maxPages)) + '%');
    ui.elapsed.textContent = P.fmt.duration(v.elapsedMs);
    ui.cancel.hidden = v.status !== 'RUNNING';
    ui.compare.hidden = !v.previousModelVersion;
    ui.send.hidden = !v.draftYaml;
    ui.message.hidden = !v.message;
    ui.message.textContent = v.message || '';
  }

  function renderPhases(v) {
    if (!changed('phases', v.phases)) return;
    P.fill(ui.phases, v.phases.map((p) =>
      h('div', 'phase ' + p.state,
        h('span', 'ic', p.state === 'DONE' ? P.icon('check', 'sm') : P.icon(PHASE_ICON[p.phase] || 'explorer', 'sm')),
        h('div', { style: { 'min-width': '0' } },
          h('div', { class: 't', text: L.phase[p.phase] || p.phase }),
          h('div', { class: 's', text: (L.phaseState[p.state] || p.state) + ' · ' + p.pagesVisited + ' səhifə' + (p.roles.length ? ' · ' + p.roles.map((r) => L.visitedAs[r] || r).join(', ') : '') }),
          h('div', { class: 's faint', text: L.phaseHelp[p.phase] || '' })))));
  }

  function renderCurrent(v) {
    const page = v.currentPage;
    if (!changed('current', page)) return;
    if (!page) {
      P.fill(ui.current, h('div', { class: 'empty-inline', text: 'Hələ səhifə açılmayıb.' }));
      return;
    }
    const img = h('img', { attrs: { alt: page.title } });
    if (page.screenshot) img.src = artifactUrl(page.screenshot);
    P.fill(ui.current,
      h('div', 'browser-bar', h('span', 'dots', h('i'), h('i'), h('i')), h('span', { class: 'ellipsis', text: page.url })),
      h('a', { class: 'current-shot', attrs: { href: page.screenshot ? artifactUrl(page.screenshot) : null, target: '_blank', rel: 'noopener' } },
        page.screenshot ? img : h('span', { class: 'thumb-none', text: 'Screenshot yoxdur' })),
      h('div', 'row wrap',
        h('strong', { text: page.title }),
        P.tag('tone-slate', L.visitedAs[page.visitedAs] || page.visitedAs),
        page.httpStatus ? P.tag(page.httpStatus >= 400 ? 'tone-red' : 'tone-green', 'HTTP ' + page.httpStatus) : null,
        page.loadMs !== null ? P.tag(page.loadMs > 3000 ? 'tone-amber' : 'tone-slate', fmt.latency(page.loadMs)) : null));
  }

  function renderActivity(v) {
    if (!changed('activity', v.activity)) return;
    P.fill(ui.activity, v.activity.length
      ? v.activity.map((e) => {
        const [label, tone] = L.activity[e.kind] || [e.kind, 'slate'];
        return h('li', null, h('span', { class: 't', text: fmt.clock(e.atMs) }), h('span', null, P.tag('tone-' + tone, label), ' ', e.text));
      })
      : h('li', { class: 'empty-inline', text: 'Fəaliyyət yoxdur.' }));
  }

  function renderVisited(v) {
    if (!changed('visited', v.visited)) return;
    ui.visitedCount.textContent = v.visited.length + ' səhifə';
    P.fill(ui.visited, v.visited.length
      ? v.visited.map((p) => h('a', { class: 'visit', attrs: { href: p.screenshot ? artifactUrl(p.screenshot) : null, target: '_blank', rel: 'noopener', title: p.url } },
        thumb(p.screenshot, p.title),
        h('div', 'meta',
          h('div', { class: 'title', text: p.title }),
          h('div', { class: 'url', text: fmt.path(p.url) }),
          h('div', 'facts-line',
            h('span', { text: L.visitedAs[p.visitedAs] || p.visitedAs }),
            p.httpStatus ? h('span', { text: '· ' + p.httpStatus }) : null,
            p.loadMs !== null ? h('span', { text: '· ' + fmt.latency(p.loadMs) }) : null))))
      : h('div', { class: 'empty-inline', text: 'Hələ səhifə yoxdur.' }));
  }

  function provenance(p) { return P.tag('prov-' + p, L.provenance[p] || p, p === 'INFERRED' ? 'Rolları müqayisədən çıxarılıb' : 'Saytda görünüb'); }

  function renderModel(v) {
    if (!changed('model', v.model)) return;
    const m = v.model;
    const actions = m.pages.reduce((n, p) => n + p.actions.length, 0);
    const forms = m.pages.reduce((n, p) => n + p.forms.length, 0);
    ui.modelSub.textContent = 'v' + m.version + ' · ' + m.pages.length + ' səhifə · ' + forms + ' form · ' + actions + ' əməliyyat';
    if (!m.pages.length) { P.fill(ui.model, h('div', { class: 'empty-inline', text: 'Model hələ boşdur.' })); return; }
    P.fill(ui.model, h('div', 'tree', m.pages.map((page, i) => {
      const body = h('div', 'page-body', h('div', { class: 'purpose', text: page.purpose }));
      for (const form of page.forms) {
        body.append(h('div', 'node',
          h('div', 'node-head', P.icon('file', 'sm'), h('span', { text: form.purpose }), provenance(form.provenance)),
          h('div', 'fields', form.fields.map((f) => h('span', 'field-chip', f.label, h('span', { class: 'ty', text: f.type }), f.required ? h('span', { class: 'req', text: '*', title: 'məcburi' }) : null)))));
      }
      for (const a of page.actions) {
        body.append(h('div', 'node',
          h('div', 'node-head', P.icon('zap', 'sm'), h('span', { text: a.name }), P.tag('tone-blue', L.actionKind[a.kind] || a.kind), provenance(a.provenance),
            a.triggersRealtime ? P.tag('tone-cyan', 'real-time', 'Başqalarının ekranını canlı yeniləyir') : null),
          (a.allowedRoles.length || a.forbiddenRoles.length)
            ? h('div', 'chip-list',
              a.allowedRoles.map((r) => P.tag('tone-green', '✓ ' + (L.role[r] || r))),
              a.forbiddenRoles.map((r) => P.tag('tone-red', '✗ ' + (L.role[r] || r), 'Bu rol etməməlidir')))
            : null));
      }
      const details = h('details', null,
        h('summary', null, P.icon('chevron', 'sm chev'),
          h('div', { style: { 'min-width': '0', flex: '1' } }, h('div', { class: 'page-title', text: page.title }), h('div', { class: 'page-url', text: page.urlPattern })),
          h('div', 'chip-list', page.reachableBy.map((r) => P.tag('tone-slate', L.visitedAs[r] || r)), provenance(page.provenance))),
        body);
      if (i < 2) details.open = true;
      return details;
    })));
    P.fill(ui.realtime, m.realtime.length
      ? m.realtime.map((r) => h('div', 'node',
        h('div', 'node-head', P.icon('zap', 'sm'), h('span', { text: L.transport[r.transport] || r.transport })),
        h('div', { class: 'help', text: r.detail }),
        h('div', 'chip-list', r.pages.map((p) => P.tag('tone-slate', p)))))
      : h('div', { class: 'empty-inline', text: 'Real-time kanal hələ görünməyib.' }));
  }

  function renderFindings(v) {
    if (!changed('findings', v.findings)) return;
    ui.findingCount.textContent = v.findings.length ? String(v.findings.length) : '';
    if (!v.findings.length) { P.fill(ui.findings, h('div', { class: 'empty-inline', text: 'Tapıntı yoxdur.' })); return; }
    const order = { HIGH: 0, MEDIUM: 1, LOW: 2 };
    const rows = [...v.findings].sort((a, b) => order[a.severity] - order[b.severity]);
    P.fill(ui.findings, h('div', 'table-wrap', h('table', 'table stack-sm',
      h('thead', null, h('tr', null, h('th', { text: 'Ciddilik' }), h('th', { text: 'Növ' }), h('th', { text: 'Səhifə' }), h('th', { text: 'Təfsilat' }))),
      h('tbody', null, rows.map((f) => h('tr', null,
        h('td', { data: { label: 'Ciddilik' } }, h('div', 'cell-v', sevBadge(f.severity))),
        h('td', { data: { label: 'Növ' } }, h('div', { class: 'cell-v', text: L.exploreFinding[f.kind] || f.kind })),
        h('td', { data: { label: 'Səhifə' } }, h('div', { class: 'cell-v mono', text: fmt.path(f.pageUrl) })),
        h('td', { data: { label: 'Təfsilat' } }, h('div', 'cell-v', f.detail), f.artifacts.length ? h('div', 'proofs', f.artifacts.map((id, i) => h('a', { text: 'Sübut ' + (i + 1) + ' ↗', attrs: { href: artifactUrl(id), target: '_blank', rel: 'noopener' } }))) : null)))))));
  }
  function sevBadge(severity) { const b = P.badge('slate', L.severity[severity] || severity, { dot: true }); b.classList.add('sev-' + severity); return b; }

  function renderQuestions(v) {
    if (!changed('unknowns', v.unknowns)) return;
    const open = v.unknowns.filter((u) => !u.answer).length;
    ui.questionCount.textContent = open ? open + ' cavabsız' : '';
    if (!v.unknowns.length) { P.fill(ui.questions, h('div', { class: 'empty-inline', text: 'Kəşfiyyatçının sualı yoxdur.' })); return; }
    P.fill(ui.questions, v.unknowns.map((u) => {
      const box = h('div', 'question', h('div', 'q', P.icon('help', 'sm'), h('span', { text: u.question })), h('div', { class: 'ctx', text: u.context }));
      if (u.answer) {
        box.append(h('div', 'answered', P.icon('checkCircle', 'sm'), h('span', { text: u.answer })));
      } else {
        const area = h('textarea', { class: 'textarea', attrs: { rows: 2, placeholder: 'Cavabınız təlimata əlavə olunacaq…', 'aria-label': 'Cavab' } });
        area.value = drafts.get(u.id) || '';
        area.addEventListener('input', () => drafts.set(u.id, area.value));
        const send = P.button('Cavab ver', { kind: 'small primary', icon: 'send', on: (e) => answer(e.currentTarget, u.id, area.value) });
        box.append(area, h('div', 'row', h('span', 'spacer'), send));
      }
      return box;
    }));
  }

  async function answer(button, id, text) {
    if (!text.trim()) { P.toast('Cavab boş ola bilməz.', 'error'); return; }
    const res = await P.busy(button, () => P.api.post('/api/exploration/unknowns/' + encodeURIComponent(id), { answer: text.trim() }));
    if (!res.ok) { P.toast(res.error, 'error'); return; }
    drafts.delete(id);
    P.toast('Cavab təlimata əlavə olundu.', 'ok');
    render(res.data);
  }

  function renderIdeas(v) {
    if (!changed('ideas', v.ideas)) return;
    ui.ideaCount.textContent = v.ideas.length ? String(v.ideas.length) : '';
    if (!v.ideas.length) { P.fill(ui.ideas, h('div', { class: 'empty-inline', text: 'Test ideyası hələ yoxdur.' })); return; }
    const ideas = [...v.ideas].sort((a, b) => a.priority - b.priority);
    P.fill(ui.ideas, h('div', 'ideas', ideas.map((i) => h('div', 'idea',
      h('span', { class: 'prio p' + Math.min(i.priority, 3), text: String(i.priority), title: 'Prioritet' }),
      h('span', 'tag-cell', P.tag('pat-' + i.pattern, L.pattern[i.pattern] || i.pattern)),
      h('div', null, h('div', { class: 'act', text: i.action }), h('div', { class: 'why', text: i.rationale }))))));
  }

  function renderDraft(v) {
    if (!changed('draft', v.draftYaml)) return;
    P.fill(ui.draft, v.draftYaml ? P.code(v.draftYaml) : h('div', { class: 'empty-inline', text: 'Model kifayət qədər dolanda ssenari layihəsi burada yaranacaq.' }));
    ui.draftSend.hidden = !v.draftYaml;
  }

  function render(v) {
    view = v;
    receivedAt = performance.now();
    ui.emptyBox.hidden = !!v;
    ui.content.hidden = !v;
    if (!v) return;
    renderHeader(v);
    renderPhases(v);
    renderCurrent(v);
    renderActivity(v);
    renderVisited(v);
    renderModel(v);
    renderFindings(v);
    renderQuestions(v);
    renderIdeas(v);
    renderDraft(v);
  }

  async function send(button) {
    const res = await P.busy(button, () => P.api.post('/api/scenarios/generate'));
    if (!res.ok) { P.toast(res.error, 'error'); return; }
    P.toast('Ssenari layihəsi göndərildi: ' + res.data.version.name + ' v' + res.data.version.version, 'ok');
    P.go('ssenariler', { id: res.data.version.id });
  }

  async function compare(button) {
    const res = await P.busy(button, () => P.api.get('/api/exploration/diff'));
    if (!res.ok) { P.toast(res.error, 'error'); return; }
    const d = res.data;
    ui.diffCard.el.hidden = false;
    if (!d) { P.fill(ui.diff, h('div', { class: 'empty-inline', text: 'Müqayisə üçün əvvəlki kəşfiyyat yoxdur.' })); return; }
    ui.diffSub.textContent = 'Model v' + d.fromVersion + ' → v' + d.toVersion + ' · ' + d.changes.length + ' dəyişiklik';
    P.fill(ui.diff, d.changes.length
      ? h('ul', 'changes', d.changes.map((c) => h('li', 'ch-' + c.kind,
        h('span', { class: 'sym', text: c.kind === 'ADDED' ? '+' : c.kind === 'REMOVED' ? '−' : '~' }),
        P.tag('tone-slate', L.subject[c.subject] || c.subject),
        h('div', null, h('strong', { text: c.name }), c.detail ? h('div', { class: 'help', text: c.detail }) : null),
        h('span', 'spacer'),
        h('span', { class: 'help', text: L.modelChange[c.kind] || c.kind }))))
      : h('div', { class: 'empty-inline', text: 'Sayt dəyişməyib.' }));
    ui.diffCard.el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  async function cancel(button) {
    const res = await P.busy(button, () => P.api.post('/api/exploration/cancel'));
    if (!res.ok) P.toast(res.error, 'error');
    else P.toast(res.data.cancelled ? 'Kəşfiyyat dayandırıldı; öyrəndikləri saxlanılır.' : 'Gedən kəşfiyyat yoxdur.', 'info');
  }

  function mount(el) {
    ui = {};
    ui.emptyBox = P.h('div', { class: 'card' }, P.empty('Hələ kəşfiyyat yoxdur', 'Təlimat bölməsində hədəfi və nəyi yoxlamaq istədiyinizi yazıb "Kəşf et" düyməsini basın. Kəşfiyyatçının işi burada canlı görünəcək.',
      P.button('Təlimata keç', { kind: 'primary', icon: 'arrowRight', on: () => P.go('telimat') })));

    ui.title = h('h2', { text: '' });
    ui.meta = h('div', 'run-meta');
    ui.status = h('span');
    ui.pages = h('span', { class: 'value mono', text: '0' });
    ui.pagesBar = h('i', { style: { width: '0' } });
    ui.elapsed = h('span', { class: 'value big mono', text: '00:00' });
    ui.cancel = P.button('Dayandır', { kind: 'small danger', icon: 'stop', on: (e) => cancel(e.currentTarget) });
    ui.compare = P.button('Əvvəlki kəşfiyyatla müqayisə et', { kind: 'small', icon: 'compare', on: (e) => compare(e.currentTarget) });
    ui.send = P.button('Ssenarilərə göndər', { kind: 'small primary', icon: 'send', on: (e) => send(e.currentTarget) });
    ui.message = h('div', { class: 'help', hidden: true });
    const header = h('section', { class: 'card run-banner', attrs: { 'aria-label': 'Kəşfiyyat' } },
      h('div', 'who', ui.title, ui.meta, ui.message),
      h('div', 'stat', h('span', { class: 'label', text: 'Səhifələr' }), ui.pages, h('div', { class: 'progress thin', style: { width: '120px', 'margin-top': '4px' } }, ui.pagesBar)),
      h('div', 'stat', h('span', { class: 'label', text: 'Müddət' }), ui.elapsed),
      ui.status,
      h('div', 'row wrap', ui.cancel, ui.compare, ui.send));

    ui.phases = h('div', 'phases');
    const current = P.card('Cari səhifə', { icon: 'eye', sub: 'Kəşfiyyatçının indi baxdığı səhifə' });
    ui.current = h('div', 'stack');
    current.body.append(ui.current);
    const activity = P.card('Fəaliyyət', { icon: 'list', flush: true, sub: 'Ən yenisi yuxarıda' });
    ui.activity = h('ul', 'activity');
    activity.body.append(ui.activity);

    const visited = P.card('Baxılan səhifələr', { icon: 'image', flush: true });
    ui.visitedCount = h('span', 'help');
    visited.actions.append(ui.visitedCount);
    ui.visited = h('div', 'visited');
    visited.body.append(ui.visited);

    const model = P.card('Sayt modeli', { icon: 'layers', flush: true, sub: '' });
    ui.modelSub = model.titles.querySelector('.sub');
    model.actions.append(h('div', 'legend', h('span', null, provenance('OBSERVED'), 'saytda görünüb'), h('span', null, provenance('INFERRED'), 'nəticə çıxarılıb')));
    ui.model = h('div');
    model.body.append(ui.model);

    const realtime = P.card('Real-time', { icon: 'zap', sub: 'Sayt canlı yeniləməni necə edir' });
    ui.realtime = h('div', 'stack');
    realtime.body.append(ui.realtime);

    const findings = P.card('Tapıntılar', { icon: 'bug', flush: true });
    ui.findingCount = h('span', 'count');
    findings.actions.append(ui.findingCount);
    ui.findings = h('div');
    findings.body.append(ui.findings);

    const questions = P.card('Suallar', { icon: 'help', flush: true, sub: 'Kəşfiyyatçı saytdan cavab tapa bilmədi' });
    ui.questionCount = h('span', 'help');
    questions.actions.append(ui.questionCount);
    ui.questions = h('div', 'questions');
    questions.body.append(ui.questions);

    const ideas = P.card('Test ideyaları', { icon: 'bulb', flush: true, sub: 'Hər əməliyyat üçün nümunələr kitabxanasından' });
    ui.ideaCount = h('span', 'count');
    ideas.actions.append(ui.ideaCount);
    ui.ideas = h('div');
    ideas.body.append(ui.ideas);

    const draft = P.card('Ssenari layihəsi', { icon: 'code', sub: 'Modeldən kodla yığılmış kampaniya YAML-ı' });
    ui.draftSend = P.button('Ssenarilərə göndər', { kind: 'small primary', icon: 'send', on: (e) => send(e.currentTarget) });
    draft.actions.append(ui.draftSend);
    ui.draft = h('div');
    draft.body.append(ui.draft);

    ui.diffCard = P.card('Əvvəlki kəşfiyyatla fərq', { icon: 'compare', flush: true, sub: '' });
    ui.diffSub = ui.diffCard.titles.querySelector('.sub');
    ui.diffCard.actions.append(h('button', { class: 'icon-btn', attrs: { type: 'button', 'aria-label': 'Bağla' }, on: { click: () => { ui.diffCard.el.hidden = true; } } }, P.icon('x', 'sm')));
    ui.diffCard.el.hidden = true;
    ui.diff = h('div');
    ui.diffCard.body.append(ui.diff);

    ui.content = h('div', { class: 'stack', hidden: true },
      header, ui.phases,
      h('div', 'grid-main-side stretch', current.el, activity.el),
      visited.el,
      ui.diffCard.el,
      h('div', 'grid-main-side', model.el, h('div', 'stack', realtime.el, questions.el)),
      findings.el,
      h('div', 'grid-2', ideas.el, draft.el));
    P.append(el, ui.emptyBox, ui.content);
    P.stream.on('exploration', (v) => { if (visible) render(v); });
    P.onTick(() => {
      if (visible && view && view.status === 'RUNNING') ui.elapsed.textContent = P.fmt.duration(view.elapsedMs + (performance.now() - receivedAt));
    });
  }

  P.register({
    id: 'kesfiyyat',
    title: 'Kəşfiyyat',
    subtitle: 'Kəşfiyyatçı agent saytı öyrənir: səhifələr, formlar, əməliyyatlar, suallar',
    icon: 'explorer',
    group: 'Hazırlıq',
    topics: ['run', 'jobs', 'exploration'],
    mount,
    show() { visible = true; for (const k of Object.keys(sigs)) delete sigs[k]; },
    hide() { visible = false; },
    badge() {
      const e = P.run.jobs && P.run.jobs.exploration;
      if (!e) return null;
      if (e.status === 'RUNNING') return { text: e.pagesVisited + '/' + e.maxPages, tone: 'live', title: 'baxılan səhifə' };
      return null;
    },
  });
})();
