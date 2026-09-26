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

/* Təlimat — a new campaign: target, plain-language instructions, team, budget; then explore, generate, run. */
(() => {
  'use strict';
  const P = window.Petek;
  const { h, L, fmt } = P;
  const DRAFT_KEY = 'petek.instructions';
  const DEFAULTS = {
    target: P.defaultTarget,
    instructions: '',
    testers: 30,
    roles: { admins: 1, managers: 5, employees: 24 },
    departments: ['Satış', 'Maliyyə', 'İnsan resursları', 'IT', 'Marketinq'],
    registration: { invite: 15, companyCode: 14 },
    budget: { maxMinutes: 30, maxStepsPerAgent: 40, maxPages: 40 },
    allowWrites: false,
  };

  let ui = null;
  let visible = false;
  let form = load();
  let autoSplit = true;
  let capacityTimer = null;
  let scenarios = [];

  function load() {
    try {
      const saved = JSON.parse(localStorage.getItem(DRAFT_KEY) || 'null');
      if (saved && typeof saved === 'object') {
        const form = Object.assign(structuredClone(DEFAULTS), saved);
        if (!String(form.target || '').trim()) form.target = DEFAULTS.target;
        return form;
      }
    } catch (e) { /* storage blocked or corrupt: start from the defaults */ }
    return structuredClone(DEFAULTS);
  }
  function save() { try { localStorage.setItem(DRAFT_KEY, JSON.stringify(form)); } catch (e) { /* storage blocked */ } }

  /** One admin, a manager per six testers (at most one per department), half of the rest by invitation. */
  function split() {
    const n = Math.max(1, form.testers);
    const managers = Math.min(Math.max(form.departments.length, 0), Math.max(0, Math.ceil((n - 1) / 6)));
    const employees = Math.max(0, n - 1 - managers);
    const joining = managers + employees;
    const invite = Math.max(managers, Math.ceil(joining / 2));
    form.roles = { admins: n >= 1 ? 1 : 0, managers, employees };
    form.registration = { invite, companyCode: joining - invite };
  }

  // ---------- fields ----------
  function field(key, label, control, hint) {
    const error = h('div', { class: 'field-error', data: { field: key } });
    const box = h('div', { class: 'field', data: { field: key } }, h('label', { text: label }), control, hint ? h('div', { class: 'hint', text: hint }) : null, error);
    ui.fields[key] = { box, error };
    return box;
  }
  function numberInput(value, min, max, onChange) {
    const input = h('input', { class: 'input num', attrs: { type: 'number', min, max, step: 1, inputmode: 'numeric' } });
    input.value = String(value);
    input.addEventListener('input', () => { const v = parseInt(input.value, 10); onChange(Number.isFinite(v) ? v : 0); });
    return input;
  }
  function clearErrors() { for (const f of Object.values(ui.fields)) { f.box.classList.remove('invalid'); f.error.textContent = ''; } }
  function showProblems(problems) {
    clearErrors();
    let first = null;
    for (const p of problems) {
      const key = p.field.startsWith('budget.') ? p.field : p.field.split('.')[0];
      const f = ui.fields[key];
      if (!f) continue;
      f.box.classList.add('invalid');
      f.error.textContent = f.error.textContent ? f.error.textContent + ' ' + p.message : p.message;
      first = first || f.box;
    }
    if (first) first.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  function syncTeam() {
    ui.admins.value = String(form.roles.admins);
    ui.managers.value = String(form.roles.managers);
    ui.employees.value = String(form.roles.employees);
    ui.invite.value = String(form.registration.invite);
    ui.companyCode.value = String(form.registration.companyCode);
    const roleSum = form.roles.admins + form.roles.managers + form.roles.employees;
    ui.roleSum.textContent = 'Cəmi ' + roleSum + ' / ' + form.testers + ' tester';
    ui.roleSum.classList.toggle('bad', roleSum !== form.testers);
    const joining = form.roles.managers + form.roles.employees;
    const regSum = form.registration.invite + form.registration.companyCode;
    ui.regSum.textContent = 'Cəmi ' + regSum + ' / ' + joining + ' (admin şirkəti özü yaradır)';
    ui.regSum.classList.toggle('bad', regSum !== joining || form.registration.invite < form.roles.managers);
    P.fill(ui.deptChips, form.departments.map((d) => P.tag('tone-slate', d)));
  }

  function setTesters(n) {
    form.testers = n;
    ui.testers.value = String(n);
    ui.range.value = String(Math.min(n, 200));
    if (autoSplit) split();
    syncTeam();
    save();
    scheduleCapacity();
  }

  // ---------- capacity ----------
  function scheduleCapacity() { clearTimeout(capacityTimer); capacityTimer = setTimeout(loadCapacity, 300); }
  async function loadCapacity() {
    const n = form.testers;
    if (!(n >= 1 && n <= 999)) { renderCapacity(null, 'Tester sayı 1–999 olmalıdır.'); return; }
    const res = await P.api.get('/api/capacity?testers=' + n);
    if (n !== form.testers) return;
    renderCapacity(res.ok ? res.data : null, res.ok ? null : res.status === 503 ? 'Tutum məsləhəti bu prosesdə qoşulmayıb.' : res.error);
  }
  function renderCapacity(c, error) {
    const box = ui.capacity;
    if (!c) {
      box.className = 'capacity unknown';
      P.fill(box, P.icon('cpu', 'sm'), h('div', { text: error || 'Tutum məlum deyil.' }));
      return;
    }
    const facts = L.limit[c.limitingFactor] + ' məhdudlaşdırır · ' + fmt.mb(c.availableMemoryMb) + ' boş yaddaş · ' + c.cpuCores + ' nüvə' + (c.measured ? ' · ölçülüb' : ' · təxmini');
    box.className = 'capacity' + (c.exceeds ? ' warn' : '');
    const text = c.exceeds
      ? h('div', null, h('b', { text: c.requested + ' tester tövsiyədən (' + c.recommended + ') çoxdur.' }), ' Run yavaşlaya bilər, amma dayandırılmayacaq. ', h('span', { class: 'faint', text: facts }))
      : h('div', null, 'Bu kompüter təxminən ', h('b', { text: c.recommended + ' tester' }), '-i rahat işlədə bilər. ', h('span', { class: 'faint', text: facts }));
    P.fill(box, P.icon(c.exceeds ? 'alert' : 'checkCircle', 'sm'), text);
    box.title = c.notes.join('\n');
  }

  // ---------- actions ----------
  function payload() {
    return {
      target: form.target.trim(),
      instructions: form.instructions,
      testers: form.testers,
      roles: form.roles,
      departments: form.departments,
      registration: form.registration,
      budget: form.budget,
      allowWrites: form.allowWrites,
    };
  }

  async function explore(button) {
    clearErrors();
    const res = await P.busy(button, () => P.api.post('/api/exploration', payload()));
    if (!res.ok) { showProblems(res.problems); P.toast(res.error, 'error'); return; }
    P.toast('Kəşfiyyat başladı: ' + fmt.host(form.target), 'ok');
    P.go('kesfiyyat');
  }

  async function generate(button) {
    const res = await P.busy(button, () => P.api.post('/api/scenarios/generate'));
    if (!res.ok) { P.toast(res.error, 'error'); return; }
    const v = res.data.version;
    P.toast('Ssenari layihəsi yaradıldı: ' + v.name + ' v' + v.version, 'ok');
    P.go('ssenariler', { id: v.id });
  }

  async function run(button) {
    clearErrors();
    const body = { scenarioId: ui.scenario.value || null, testers: form.testers, headful: ui.headful.checked, target: form.target.trim() || null };
    const res = await P.busy(button, () => P.api.post('/api/runs', body));
    if (!res.ok) { showProblems(res.problems); P.toast(res.error, 'error'); return; }
    P.toast('Run başladı: ' + res.data.runId, 'ok');
    P.go('agentler');
  }

  async function loadScenarios() {
    const res = await P.api.get('/api/scenarios');
    scenarios = res.ok ? res.data.filter((s) => s.runnable) : [];
    const previous = ui.scenario.value;
    P.fill(ui.scenario, scenarios.length
      ? scenarios.map((s) => h('option', { text: s.name + ' · v' + s.version + ' · ' + L.scenarioStatus[s.status], attrs: { value: s.id } }))
      : h('option', { text: 'Təsdiqlənmiş ssenari yoxdur', attrs: { value: '' } }));
    if (scenarios.some((s) => s.id === previous)) ui.scenario.value = previous;
    ui.runBtn.disabled = !scenarios.length;
    renderFlow();
  }

  // ---------- jobs and flow ----------
  function renderJobs() {
    const jobs = P.run.jobs;
    const exploration = jobs && jobs.exploration;
    const header = P.run.header;
    const lines = [];
    if (exploration && exploration.status === 'RUNNING') {
      lines.push(h('div', 'job-line', P.icon('explorer', 'sm'),
        h('span', { class: 'spacer', text: 'Kəşfiyyat gedir · ' + exploration.pagesVisited + ' / ' + exploration.maxPages + ' səhifə' }),
        P.button('Dayandır', { kind: 'small danger', icon: 'stop', on: (e) => cancel(e.currentTarget, '/api/exploration/cancel', 'Kəşfiyyat dayandırıldı') })));
    }
    if (header && header.run && header.run.phase === 'RUNNING') {
      lines.push(h('div', 'job-line', P.icon('agents', 'sm'),
        h('span', { class: 'spacer', text: 'Run gedir · ' + (header.run.campaignName || header.run.runId) }),
        P.button('Dayandır', { kind: 'small danger', icon: 'stop', on: (e) => cancel(e.currentTarget, '/api/runs/cancel', 'Run dayandırılır') })));
    }
    P.fill(ui.jobs, lines);
    ui.jobs.hidden = !lines.length;
    renderFlow();
  }
  async function cancel(button, path, done) {
    const res = await P.busy(button, () => P.api.post(path));
    if (!res.ok) P.toast(res.error, 'error');
    else P.toast(res.data.cancelled ? done : 'Dayandırılacaq iş yoxdur', res.data.cancelled ? 'ok' : 'info');
  }

  function renderFlow() {
    if (!ui) return;
    const exploration = P.run.jobs && P.run.jobs.exploration;
    const header = P.run.header;
    const steps = [
      { t: 'Təlimat', s: form.target ? fmt.host(form.target) : 'Hədəf və məqsəd', done: !!form.target },
      { t: 'Kəşfiyyat', s: exploration ? L.explorationStatus[exploration.status] : 'Sayt modeli və suallar', done: !!exploration && exploration.status !== 'RUNNING', active: !!exploration && exploration.status === 'RUNNING' },
      { t: 'Ssenari', s: scenarios.length ? scenarios.length + ' təsdiqlənmiş' : 'Layihə → təsdiq', done: scenarios.length > 0 },
      { t: 'Run', s: header && header.run && header.run.runId ? P.runStatus(header.run)[1] : 'N tester eyni anda', done: !!(header && header.run && header.run.phase === 'FINISHED'), active: !!(header && header.run && header.run.phase === 'RUNNING') },
    ];
    let activeSet = steps.some((s) => s.active);
    P.fill(ui.flow, steps.map((s, i) => {
      const active = s.active || (!activeSet && !s.done && (activeSet = true));
      return h('div', 'flow-step' + (s.done ? ' done' : '') + (active ? ' active' : ''),
        h('span', 'n', s.done ? P.icon('check', 'sm') : String(i + 1)),
        h('div', { style: { 'min-width': '0' } }, h('div', { class: 't', text: s.t }), h('div', { class: 's ellipsis', text: s.s })));
    }));
  }

  function mount(el) {
    ui = { fields: {} };
    ui.flow = h('div', 'flow');

    const target = h('input', { class: 'input', attrs: { type: 'url', placeholder: 'https://staging.example.com', autocomplete: 'off', spellcheck: 'false' } });
    target.value = form.target;
    target.addEventListener('input', () => { form.target = target.value; save(); renderFlow(); });
    const text = h('textarea', { class: 'textarea', attrs: { rows: 8, placeholder: 'Məsələn: Admin elan yaradır, bütün işçilər onu 10 saniyə ərzində real vaxtda görməlidir. Tapşırıq yaratma və menecerin təsdiqi axınını da yoxla. Ödəniş bölməsinə toxunma.' } });
    text.value = form.instructions;
    const count = h('span', 'faint');
    const updateCount = () => { count.textContent = fmt.int(form.instructions.length) + ' / 20 000'; };
    text.addEventListener('input', () => { form.instructions = text.value; updateCount(); save(); });
    updateCount();
    const what = P.card('Hədəf və təlimat', { icon: 'target', sub: 'Hansı sayt və nəyi yoxlamaq istəyirsiniz' });
    P.append(what.body, h('div', 'form-grid',
      field('target', 'Hədəf sayt', target, 'Yalnız test mühiti: istehsal ünvanları əlavə icazə olmadan qəbul edilmir.'),
      field('instructions', 'Nəyi test edim?', text),
      h('div', 'row', h('span', 'spacer'), count)));

    ui.testers = numberInput(form.testers, 1, 999, (v) => setTesters(v));
    ui.range = h('input', { class: 'range', attrs: { type: 'range', min: 1, max: 200, step: 1, 'aria-label': 'Tester sayı' } });
    ui.range.value = String(Math.min(form.testers, 200));
    ui.range.addEventListener('input', () => setTesters(parseInt(ui.range.value, 10)));
    ui.capacity = h('div', 'capacity unknown', P.icon('cpu', 'sm'), h('div', { text: 'Tutum yoxlanılır…' }));
    const onRole = (key) => (v) => { form.roles[key] = v; autoSplit = false; ui.auto.checked = false; syncTeam(); save(); };
    ui.admins = numberInput(form.roles.admins, 0, 999, onRole('admins'));
    ui.managers = numberInput(form.roles.managers, 0, 999, onRole('managers'));
    ui.employees = numberInput(form.roles.employees, 0, 999, onRole('employees'));
    ui.roleSum = h('div', 'sum-line');
    const departments = h('input', { class: 'input', attrs: { type: 'text', placeholder: 'Satış, Maliyyə, IT', autocomplete: 'off' } });
    departments.value = form.departments.join(', ');
    departments.addEventListener('input', () => {
      form.departments = departments.value.split(',').map((d) => d.trim()).filter(Boolean);
      if (autoSplit) split();
      syncTeam();
      save();
    });
    ui.deptChips = h('div', 'dept-chips');
    const onReg = (key) => (v) => { form.registration[key] = v; autoSplit = false; ui.auto.checked = false; syncTeam(); save(); };
    ui.invite = numberInput(form.registration.invite, 0, 999, onReg('invite'));
    ui.companyCode = numberInput(form.registration.companyCode, 0, 999, onReg('companyCode'));
    ui.regSum = h('div', 'sum-line');
    ui.auto = h('input', { attrs: { type: 'checkbox', checked: true } });
    ui.auto.addEventListener('change', () => { autoSplit = ui.auto.checked; if (autoSplit) { split(); syncTeam(); save(); } });

    const team = P.card('Komanda', { icon: 'team', sub: 'Neçə tester, hansı rollarla və necə qoşulsunlar' });
    team.actions.append(h('label', 'switch', ui.auto, h('span', { text: 'Avtomatik bölgü' })));
    P.append(team.body, h('div', 'form-grid',
      field('testers', 'Tester sayı', h('div', 'tester-row', ui.testers, ui.range)),
      ui.capacity,
      h('div', 'divider'),
      field('roles', 'Rollar', h('div', 'form-grid cols-3',
        h('div', 'field', h('span', { class: 'label hint', text: 'Admin' }), ui.admins),
        h('div', 'field', h('span', { class: 'label hint', text: 'Menecer' }), ui.managers),
        h('div', 'field', h('span', { class: 'label hint', text: 'İşçi' }), ui.employees)), null),
      ui.roleSum,
      field('departments', 'Şöbələr', h('div', 'stack', departments, ui.deptChips), 'Vergüllə ayırın. Hər şöbəyə bir menecer düşür.'),
      field('registration', 'Qeydiyyat', h('div', 'form-grid cols-2',
        h('div', 'field', h('span', { class: 'label hint', text: 'Dəvətlə' }), ui.invite),
        h('div', 'field', h('span', { class: 'label hint', text: 'Şirkət kodu ilə' }), ui.companyCode)),
      'Menecerlər həmişə dəvətlə qoşulur; qalan dəvətlər işçilərə düşür.'),
      ui.regSum));

    const budget = P.card('Büdcə', { icon: 'wallet', sub: 'Vaxt, addım və kəşfiyyat limitləri' });
    const minutes = numberInput(form.budget.maxMinutes, 1, 480, (v) => { form.budget.maxMinutes = v; save(); });
    const steps = numberInput(form.budget.maxStepsPerAgent, 1, 500, (v) => { form.budget.maxStepsPerAgent = v; save(); });
    const pages = numberInput(form.budget.maxPages, 1, 1000, (v) => { form.budget.maxPages = v; save(); });
    const writes = h('input', { attrs: { type: 'checkbox' } });
    writes.checked = !!form.allowWrites;
    writes.addEventListener('change', () => { form.allowWrites = writes.checked; save(); });
    P.append(budget.body, h('div', 'form-grid',
      h('div', 'form-grid cols-3',
        field('budget.maxMinutes', 'Vaxt (dəqiqə)', minutes),
        field('budget.maxStepsPerAgent', 'Agent başına addım', steps),
        field('budget.maxPages', 'Kəşfiyyat: səhifə', pages)),
      h('label', 'check', writes, h('span', null, h('b', { text: 'Sınaq toxunuşu. ' }), 'Kəşfiyyatçı hər yaratma formunu bir dəfə zərərsiz məlumatla göndərsin (yalnız test şirkətində, heç nə silinmir).'))));

    // actions
    ui.jobs = h('div', { class: 'stack', hidden: true });
    ui.scenario = h('select', { class: 'select', attrs: { 'aria-label': 'Ssenari' } });
    ui.headful = h('input', { attrs: { type: 'checkbox' } });
    const exploreBtn = P.button('Kəşf et', { kind: 'primary block', icon: 'explorer', on: (e) => explore(e.currentTarget) });
    const generateBtn = P.button('Ssenari yarat', { kind: 'block', icon: 'sparkles', on: (e) => generate(e.currentTarget) });
    ui.runBtn = P.button('Run et', { kind: 'dark block', icon: 'play', on: (e) => run(e.currentTarget) });
    const actions = P.card('Başla', { icon: 'play', class: 'action-card' });
    P.append(actions.body,
      ui.jobs,
      h('div', 'action-block', exploreBtn, h('div', { class: 'help', text: 'Kəşfiyyatçı saytı gəzir, sayt modelini qurur, suallar verir və test ideyaları təklif edir.' })),
      h('div', 'action-block', generateBtn, h('div', { class: 'help', text: 'Son kəşfiyyatın modelindən ssenari layihəsi (YAML) hazırlanır; təsdiqdən sonra run oluna bilər.' })),
      h('div', 'divider'),
      field('scenario', 'Təsdiqlənmiş ssenari', ui.scenario),
      h('label', 'switch', ui.headful, h('span', { text: 'Brauzerləri göstər (headful)' })),
      h('div', 'action-block', ui.runBtn, h('div', { class: 'help', text: 'Seçilmiş ssenari yuxarıdakı tester sayı ilə işə düşür; gedişatı "Agentlər" və "Orkestrator" göstərir.' })));

    const how = P.card('Necə işləyir', { icon: 'help' });
    P.append(how.body, h('ol', 'steps-help',
      h('li', null, h('b', { text: '1' }), h('span', { text: 'Təlimatı yazın: hədəf və nəyin vacib olduğu.' })),
      h('li', null, h('b', { text: '2' }), h('span', { text: 'Kəşfiyyatçı saytı öyrənir; suallarına cavab verin.' })),
      h('li', null, h('b', { text: '3' }), h('span', { text: 'Ssenari layihəsini yoxlayıb təsdiqləyin.' })),
      h('li', null, h('b', { text: '4' }), h('span', { text: 'Run edin: N tester eyni anda, sübutlu hesabatla.' }))));

    // accounts the explorer may sign in with (bring your own accounts): the password goes to .env, never to the database
    const accounts = P.card('Hesablar', { icon: 'team', sub: 'Kəşfiyyatçı bu saytda sizin test hesablarınızla daxil olsun' });
    const accountList = h('div', 'stack');
    const role = h('input', { class: 'input', attrs: { type: 'text', placeholder: 'admin', autocomplete: 'off', 'aria-label': 'Rol' } });
    const email = h('input', { class: 'input', attrs: { type: 'email', placeholder: 'test@sirket.az', autocomplete: 'off', 'aria-label': 'E-poçt' } });
    const password = h('input', { class: 'input', attrs: { type: 'password', autocomplete: 'new-password', 'aria-label': 'Parol' } });
    function renderAccounts(list) {
      accountList.replaceChildren(...(list.length ? list.map((a) => h('div', 'row', h('b', { text: a.role }), h('span', { text: a.email || 'sessiya faylı' }),
        h('span', { class: 'faint', text: a.site + (a.passwordVariable ? ' · parol .env-də: ' + a.passwordVariable : '') }))) : [h('div', { class: 'faint', text: 'Hələ hesab verilməyib: kəşfiyyatçı test şirkəti, özü qeydiyyat və ya anonim yolla gedir.' })]));
    }
    async function loadAccounts() { const res = await P.api.get('/api/accounts'); if (res.ok) renderAccounts(res.data.accounts || []); }
    async function addAccount(button) {
      const res = await P.busy(button, () => P.api.post('/api/accounts', { target: form.target, role: role.value, email: email.value, password: password.value }));
      password.value = '';
      if (res.ok) { renderAccounts(res.data.accounts || []); P.toast('Hesab saxlanıldı; parol yalnız .env-dədir.', 'ok'); }
      else P.toast((res.problems[0] && res.problems[0].message) || res.error, 'error');
    }
    P.append(accounts.body, h('div', 'form-grid',
      h('div', 'form-grid cols-3', role, email, password),
      P.button('Hesab əlavə et', { icon: 'plus', on: (e) => addAccount(e.currentTarget) }),
      h('div', { class: 'help', text: 'Yalnız test hesabları, real istifadəçi hesabı heç vaxt. Parol .env faylına yazılır və bir daha göstərilmir.' }),
      accountList));
    loadAccounts();

    P.append(el, ui.flow, h('div', 'grid-main-side',
      h('div', 'stack', what.el, accounts.el, team.el, budget.el),
      h('div', 'stack sticky-side', actions.el, how.el)));
    if (autoSplit && form.roles.admins + form.roles.managers + form.roles.employees !== form.testers) split();
    syncTeam();
    P.onRun(() => { if (visible) renderJobs(); });
  }

  P.register({
    id: 'telimat',
    title: 'Təlimat',
    subtitle: 'Yeni kampaniya: hədəf, nəyi test etmək, komanda və büdcə',
    icon: 'instructions',
    group: 'Hazırlıq',
    topics: ['run', 'jobs'],
    mount,
    show() { visible = true; loadCapacity(); loadScenarios(); renderJobs(); },
    hide() { visible = false; },
  });
})();
