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

/* Quraşdırma — is everything a test needs in place: the site answers, its ownership, the AI, how many testers. */
(() => {
  'use strict';
  const P = window.Petek;
  const { h, fmt } = P;
  // Per site: a panel opened for another site on the same address starts on its own setup.
  const DONE_KEY = 'petek.setupDone:' + (P.defaultTarget || '');

  let ui = null;
  let capacityTimer = null;
  let doneHere = false;

  /**
   * Whether the owner finished the setup for this site once; until then the panel opens on this screen. When the browser
   * keeps nothing (storage blocked), it is this page's own memory, which starts as not done.
   */
  P.setupDone = () => {
    if (doneHere) return true;
    try { return localStorage.getItem(DONE_KEY) === '1'; } catch (e) { return false; }
  };
  function markDone() {
    doneHere = true;
    try { localStorage.setItem(DONE_KEY, '1'); } catch (e) { /* storage blocked: remembered by this page only */ }
  }

  // ---------- one step ----------
  function step(n, title, sub, icon) {
    const card = P.card(n + ' · ' + title, { icon, sub });
    const status = h('div', 'setup-status');
    const body = h('div', 'stack');
    P.append(card.body, status, body);
    return { el: card.el, actions: card.actions, status, body };
  }
  function state(box, tone, text) {
    P.fill(box, P.badge(tone, text, { dot: true }));
  }

  // ---------- 1. the site ----------
  async function checkSite(button) {
    state(ui.site.status, 'slate', 'Yoxlanılır…');
    const res = await P.busy(button, () => P.api.post('/api/readiness/site'));
    if (!res.ok) { state(ui.site.status, 'red', res.error); return; }
    if (res.data.reachable) state(ui.site.status, 'green', 'Sayt cavab verir');
    else state(ui.site.status, 'red', 'Sayt cavab vermir: ' + res.data.detail);
  }

  // ---------- 2. ownership ----------
  async function checkOwnership(button, fresh) {
    state(ui.owner.status, 'slate', fresh ? 'Sübut axtarılır…' : 'Yoxlanılır…');
    const res = await P.busy(button, () => P.api.post('/api/readiness/ownership' + (fresh ? '?fresh=true' : '')));
    if (!res.ok) { state(ui.owner.status, 'red', res.error); return; }
    renderOwnership(res.data, fresh);
  }
  function renderOwnership(o, fresh) {
    const body = ui.owner.body;
    if (o.state === 'EXEMPT') {
      state(ui.owner.status, 'green', 'Təsdiq lazım deyil');
      P.fill(body, h('div', { class: 'help', text: o.host + ' lokal və ya daxili şəbəkə ünvanıdır: bütün testlər (yazanlar da) təsdiqsiz işləyir.' }));
      return;
    }
    if (o.state === 'VERIFIED') {
      state(ui.owner.status, 'green', 'Təsdiqlənib (' + (o.method === 'dns' ? 'DNS qeydi' : 'fayl') + ')');
      P.fill(body, h('div', { class: 'help', text: o.host + ' sizin saytınız kimi təsdiqlənib: bütün testlər, qeydiyyat və formlar da daxil, işləyir.' }));
      return;
    }
    state(ui.owner.status, 'amber', fresh ? 'Sübut hələ tapılmadı' : 'Təsdiqlənməyib');
    const line = h('code', { class: 'mono proof-line', text: o.proofLine });
    const copy = P.button('Kopyala', { kind: 'small', icon: 'file', on: () => copyText(o.proofLine) });
    const verify = P.button('Yoxla', { kind: 'small primary', icon: 'refresh', on: (e) => checkOwnership(e.currentTarget, true) });
    P.fill(body,
      h('div', { class: 'help', text: 'Yalnız oxuyan testlər (linklər, konsol, sürət, mobil görünüş, şəkillər, meta) təsdiqsiz də istənilən sayda testerlə işləyir. Qeydiyyat və form göndərmək kimi yazan testlər üçün saytın sizin olduğunu bir dəfə təsdiqləyin.' }),
      h('div', 'callout',
        h('div', null, h('b', { text: 'Fayl: ' }), h('a', { text: o.fileUrl, attrs: { href: o.fileUrl, target: '_blank', rel: 'noopener' } })),
        h('div', { class: 'help', text: 'Bu fayla aşağıdakı sətri yazın (fayl başqa hosta yönləndirilmədən açılmalıdır):' }),
        h('div', 'row wrap', line, copy),
        o.dnsName ? h('div', { class: 'help', text: 'Və ya DNS-ə TXT qeydi əlavə edin: ' + o.dnsName + ', dəyəri eyni sətirdir.' }) : null),
      h('div', 'row wrap', verify, o.looked.length ? h('span', { class: 'faint', text: 'Baxıldı: ' + o.looked.join('; ') }) : null));
  }
  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      P.toast('Kopyalandı', 'ok');
    } catch (e) {
      P.toast('Kopyalamaq alınmadı; sətri əllə seçin.', 'error');
    }
  }

  // ---------- 3. the AI ----------
  function renderAi(ai) {
    const facts = h('div', 'run-meta',
      h('span', null, P.icon('sparkles', 'sm'), ai.configured ? ai.provider + ' · ' + ai.model : 'AI qoşulmayıb'),
      ai.reason ? h('span', { class: 'faint', text: ai.reason }) : null,
      ai.fallbacks.length ? h('span', { class: 'faint', text: 'sonra: ' + ai.fallbacks.join(', ') }) : null);
    const help = ai.configured
      ? 'Sınaq AI-a bir kiçik sorğu göndərir (AI planınızdan bir sorğu). "Dəyiş" ilə başqa AI seçə bilərsiniz.'
      : 'Kod yoxlamaları AI-sız işləyir. Kəşfiyyatçının səhifə analizi, `do` addımları və tirajın bir hissəsi üçün AI lazımdır: "Dəyiş" ilə seçin ("Avtomatik" kompüterdəki AI-ı özü tapır).';
    P.fill(ui.ai.body, facts, h('div', { class: 'help', text: help }), ui.aiChooser);
    state(ui.ai.status, ai.configured ? 'slate' : 'amber', ai.configured ? 'Sınanmayıb' : 'AI yoxdur');
    ui.aiBtn.hidden = !ai.configured;
  }
  // The AI chosen here (Faza 23): kept in the configuration file, used from the next test on, without a restart.
  async function openAiChooser(button) {
    const res = await P.busy(button, () => P.api.get('/api/readiness/ai-options'));
    if (!res.ok) { P.toast(res.error, 'error'); return; }
    const o = res.data;
    ui.aiChooser.hidden = false;
    if (o.unavailable) { P.fill(ui.aiChooser, h('div', { class: 'help', text: o.unavailable })); return; }
    const select = h('select', { class: 'select', attrs: { 'aria-label': 'AI' } },
      ...o.options.map((x) => h('option', { text: x.label + (x.available ? '' : ' (bu kompüterdə tapılmadı)'), attrs: { value: x.provider } })));
    select.value = o.chosen;
    const model = h('input', { class: 'input', attrs: { type: 'text', placeholder: 'Model (boş: alətin öz modeli)', autocomplete: 'off', 'aria-label': 'Model' } });
    model.value = o.model || '';
    const endpoint = h('input', { class: 'input', attrs: { type: 'url', placeholder: 'http://localhost:11434/v1', autocomplete: 'off', 'aria-label': 'Endpoint' } });
    endpoint.value = o.endpoint || '';
    const key = h('input', { class: 'input', attrs: { type: 'password', autocomplete: 'new-password', placeholder: o.keySet ? 'Açar var (boş qalsa saxlanır)' : 'API açarı', 'aria-label': 'API açarı' } });
    const needs = () => (o.options.find((x) => x.provider === select.value) || { needs: [] }).needs;
    const sync = () => {
      const n = needs();
      model.hidden = !n.includes('model');
      endpoint.hidden = !n.includes('endpoint');
      key.hidden = !n.includes('key');
    };
    select.addEventListener('change', sync);
    sync();
    async function choose(b) {
      const n = needs();
      const body = {
        provider: select.value,
        model: n.includes('model') ? model.value : '',
        endpoint: n.includes('endpoint') ? endpoint.value : '',
        key: n.includes('key') ? key.value : '',
      };
      const r = await P.busy(b, () => P.api.post('/api/readiness/ai-choice', body));
      key.value = '';
      if (!r.ok) { P.toast((r.problems && r.problems[0] && r.problems[0].message) || r.error, 'error'); return; }
      P.fill(ui.aiChooser);
      ui.aiChooser.hidden = true;
      renderAi(r.data.ai);
      P.toast('AI dəyişdi: növbəti testdən işlənir və konfiqurasiya faylında saxlanıldı.', 'ok');
    }
    P.fill(ui.aiChooser, h('div', 'form-grid',
      select,
      h('div', 'form-grid cols-3', model, endpoint, key),
      h('div', 'row wrap',
        P.button('Yadda saxla', { kind: 'small primary', icon: 'check', on: (e) => choose(e.currentTarget) }),
        h('span', { class: 'faint', text: 'Açar yalnız konfiqurasiya faylına yazılır və bir daha göstərilmir.' }))));
  }

  async function testAi(button) {
    state(ui.ai.status, 'slate', 'AI-dan cavab gözlənilir…');
    const res = await P.busy(button, () => P.api.post('/api/readiness/ai'));
    if (!res.ok) { state(ui.ai.status, 'red', res.error); return; }
    const a = res.data;
    const took = (a.millis / 1000).toLocaleString('az', { maximumFractionDigits: 1 }) + ' san';
    if (a.ok) state(ui.ai.status, 'green', 'AI cavab verdi' + (a.model ? ' (' + a.model + ')' : '') + ' · ' + took);
    else state(ui.ai.status, 'red', 'AI cavab vermədi: ' + a.detail);
  }

  // ---------- 4. testers ----------
  function setTesters(n) {
    if (!(n >= 1 && n <= 999)) return;
    P.testers.set(n);
    ui.count.value = String(n);
    ui.range.value = String(Math.min(n, 200));
    clearTimeout(capacityTimer);
    capacityTimer = setTimeout(loadCapacity, 300);
  }
  async function loadCapacity() {
    const n = P.testers.get();
    const res = await P.api.get('/api/capacity?testers=' + n);
    if (n !== P.testers.get()) return;
    if (!res.ok) { P.fill(ui.capacity, h('div', { class: 'help', text: res.error })); return; }
    const c = res.data;
    const text = c.exceeds
      ? n + ' tester bu kompüter üçün tövsiyədən (' + c.recommended + ') çoxdur: run yavaşlaya bilər, amma hamısı işə düşəcək.'
      : 'Bu kompüter təxminən ' + c.recommended + ' testeri rahat işlədə bilər.';
    ui.capacity.className = 'capacity' + (c.exceeds ? ' warn' : '');
    P.fill(ui.capacity, P.icon(c.exceeds ? 'alert' : 'checkCircle', 'sm'),
      h('div', null, text, ' ', h('span', { class: 'faint', text: fmt.mb(c.availableMemoryMb) + ' boş yaddaş · ' + c.cpuCores + ' nüvə' })));
  }

  // ---------- mount ----------
  async function load() {
    const res = await P.api.get('/api/readiness');
    if (!res.ok) { P.fill(ui.site.body, h('div', { class: 'help', text: res.error })); return; }
    const r = res.data;
    P.fill(ui.site.body,
      h('div', 'run-meta', h('span', null, P.icon('globe', 'sm'), r.target)),
      h('div', { class: 'help', text: r.configuration ? 'Ayarlar bu faylda saxlanılır: ' + r.configuration : 'Ayarlar mühit dəyişənlərindən gəlir.' }));
    renderAi(r.ai);
    checkSite(null);
    checkOwnership(null, false);
  }

  function mount(el) {
    ui = {};
    ui.site = step(1, 'Sayt', 'Test ediləcək sayt cavab verirmi', 'target');
    ui.site.actions.append(P.button('Yenidən yoxla', { kind: 'small', icon: 'refresh', on: (e) => checkSite(e.currentTarget) }));
    ui.owner = step(2, 'Sahiblik', 'Yazan testlər üçün saytın sizin olduğunun sübutu', 'lock');
    ui.ai = step(3, 'AI', 'Kəşfiyyatçı və testerlərin ağlı', 'sparkles');
    ui.aiBtn = P.button('Sına', { kind: 'small', icon: 'zap', on: (e) => testAi(e.currentTarget) });
    ui.aiChooser = h('div', 'stack');
    ui.aiChooser.hidden = true;
    ui.ai.actions.append(P.button('Dəyiş', { kind: 'small', icon: 'sparkles', on: (e) => openAiChooser(e.currentTarget) }), ui.aiBtn);
    ui.testers = step(4, 'Testerlər', 'Eyni anda neçə tester işləsin', 'team');
    ui.count = h('input', { class: 'input num', attrs: { type: 'number', min: 1, max: 999, step: 1, inputmode: 'numeric', 'aria-label': 'Tester sayı' } });
    ui.count.addEventListener('input', () => setTesters(parseInt(ui.count.value, 10)));
    ui.range = h('input', { class: 'range', attrs: { type: 'range', min: 1, max: 200, step: 1, 'aria-label': 'Tester sayı' } });
    ui.range.addEventListener('input', () => setTesters(parseInt(ui.range.value, 10)));
    ui.capacity = h('div', 'capacity unknown', P.icon('cpu', 'sm'), h('div', { text: 'Tutum yoxlanılır…' }));
    P.fill(ui.testers.status, P.badge('slate', 'Seçdiyiniz qədər tester işə düşür', { dot: true }));
    P.append(ui.testers.body,
      h('div', 'tester-row', ui.count, ui.range),
      ui.capacity,
      h('div', { class: 'help', text: 'Bu say "Təlimat" ekranında və run-larda istifadə olunur. Limit yoxdur: tövsiyə yalnız kompüterin rahat işlədə biləcəyi saydır.' }));
    const ready = P.button('Hazırdır: saytı test et', { kind: 'primary', icon: 'arrowRight', on: () => { markDone(); P.go('telimat'); } });
    P.append(el, h('div', 'stack',
      h('div', { class: 'help', text: 'Bir dəfə baxın: sayt cavab verirsə, AI və tester sayı seçilibsə, "Təlimat" ekranında "Test et" ilə başlayın. Hər şeyi sonra da buradan dəyişə bilərsiniz.' }),
      ui.site.el, ui.owner.el, ui.ai.el, ui.testers.el,
      h('div', 'row', h('span', 'spacer'), ready)));
    const n = P.testers.get();
    ui.count.value = String(n);
    ui.range.value = String(Math.min(n, 200));
  }

  P.register({
    id: 'qurasdirma',
    title: 'Quraşdırma',
    subtitle: 'Sayt, sahiblik, AI və testerlər: test üçün hər şey hazırdırmı',
    icon: 'checkCircle',
    group: 'Hazırlıq',
    topics: ['run', 'jobs'],
    mount,
    show() { load(); loadCapacity(); },
    hide() {},
  });
})();
