/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

/* Pətək panel — shared core: DOM helpers, icons, labels, formatting, API, live stream, router and shared pieces.
   Every value from the server is written with textContent (never innerHTML), so nothing it sends can become markup. */
(() => {
  'use strict';
  const P = (window.Petek = {});

  // ---------- DOM ----------
  P.$ = (id) => document.getElementById(id);

  /** h('div', {class, text, title, hidden, attrs, data, on, style}, ...children) or h('div', 'class', ...children). */
  P.h = (tag, opts, ...children) => {
    const el = document.createElement(tag);
    if (typeof opts === 'string') el.className = opts;
    else if (opts) {
      if (opts.class) el.className = opts.class;
      if (opts.text !== undefined && opts.text !== null) el.textContent = String(opts.text);
      if (opts.title) el.title = opts.title;
      if (opts.hidden) el.hidden = true;
      if (opts.attrs) for (const [k, v] of Object.entries(opts.attrs)) if (v !== null && v !== undefined && v !== false) el.setAttribute(k, v === true ? '' : String(v));
      if (opts.data) for (const [k, v] of Object.entries(opts.data)) if (v !== null && v !== undefined) el.dataset[k] = String(v);
      if (opts.on) for (const [k, v] of Object.entries(opts.on)) el.addEventListener(k, v);
      if (opts.style) for (const [k, v] of Object.entries(opts.style)) el.style.setProperty(k, String(v));
    }
    P.append(el, ...children);
    return el;
  };
  P.append = (el, ...children) => {
    for (const c of children.flat()) {
      if (c === null || c === undefined || c === false) continue;
      el.append(c instanceof Node ? c : document.createTextNode(String(c)));
    }
    return el;
  };
  P.fill = (el, ...children) => { el.textContent = ''; return P.append(el, ...children); };

  // ---------- icons (stroke paths on a 24px grid) ----------
  const ICONS = {
    instructions: ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z', 'M14 2v6h6', 'M16 13H8', 'M16 17H8', 'M10 9H8'],
    explorer: [{ c: [12, 12, 10] }, 'm16.24 7.76-2.12 6.36-6.36 2.12 2.12-6.36z'],
    scenarios: ['m12 2 10 5-10 5L2 7z', 'm2 17 10 5 10-5', 'm2 12 10 5 10-5'],
    orchestrator: [{ r: [3, 3, 6, 6, 1.5] }, { r: [15, 15, 6, 6, 1.5] }, { r: [15, 3, 6, 6, 1.5] }, 'M9 6h6', 'M18 9v6', 'M6 9v5a3 3 0 0 0 3 3h6'],
    agents: ['M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2', { c: [9, 7, 4] }, 'M22 21v-2a4 4 0 0 0-3-3.87', 'M16 3.13a4 4 0 0 1 0 7.75'],
    reports: ['M3 3v18h18', 'M18 17V9', 'M13 17V5', 'M8 17v-3'],
    sun: [{ c: [12, 12, 4] }, 'M12 2v2', 'M12 20v2', 'm4.93 4.93 1.41 1.41', 'm17.66 17.66 1.41 1.41', 'M2 12h2', 'M20 12h2', 'm6.34 17.66-1.41 1.41', 'm19.07 4.93-1.41 1.41'],
    moon: ['M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9z'],
    monitor: [{ r: [2, 3, 20, 14, 2] }, 'M8 21h8', 'M12 17v4'],
    menu: ['M4 6h16', 'M4 12h16', 'M4 18h16'],
    x: ['M18 6 6 18', 'm6 6 12 12'],
    play: ['m7 4 13 8-13 8z'],
    stop: [{ r: [6, 6, 12, 12, 2] }],
    search: [{ c: [11, 11, 7] }, 'm21 21-4.3-4.3'],
    check: ['M20 6 9 17l-5-5'],
    checkCircle: [{ c: [12, 12, 10] }, 'm8.5 12.5 2.5 2.5 5-5.5'],
    alert: ['M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z', 'M12 9v4', 'M12 17h.01'],
    external: ['M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6', 'M15 3h6v6', 'M10 14 21 3'],
    clock: [{ c: [12, 12, 10] }, 'M12 6v6l4 2'],
    globe: [{ c: [12, 12, 10] }, 'M2 12h20', 'M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z'],
    cpu: [{ r: [4, 4, 16, 16, 2] }, { r: [9, 9, 6, 6, 1] }, 'M9 1v3', 'M15 1v3', 'M9 20v3', 'M15 20v3', 'M20 9h3', 'M20 14h3', 'M1 9h3', 'M1 14h3'],
    sparkles: ['M12 3 13.9 8.1 19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z', 'M19 17v4', 'M17 19h4'],
    arrowRight: ['M5 12h14', 'm12 5 7 7-7 7'],
    refresh: ['M21 12a9 9 0 1 1-3-6.7L21 8', 'M21 3v5h-5'],
    send: ['m22 2-7 20-4-9-9-4z', 'M22 2 11 13'],
    compare: [{ c: [18, 18, 3] }, { c: [6, 6, 3] }, 'M13 6h3a2 2 0 0 1 2 2v7', 'M11 18H8a2 2 0 0 1-2-2V9'],
    lock: [{ r: [3, 11, 18, 11, 2] }, 'M7 11V7a5 5 0 0 1 10 0v4'],
    zap: ['M13 2 3 14h9l-1 8 10-12h-9z'],
    help: [{ c: [12, 12, 10] }, 'M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3', 'M12 17h.01'],
    bulb: ['M9 18h6', 'M10 22h4', 'M15.09 14c.18-.98.65-1.74 1.41-2.5A4.65 4.65 0 0 0 18 8 6 6 0 0 0 6 8c0 1 .23 2.23 1.5 3.5A4.61 4.61 0 0 1 8.91 14'],
    bug: [{ r: [8, 6, 8, 14, 4] }, 'M12 20v-9', 'M4 13h4', 'M16 13h4', 'm5 7 3 2', 'm19 7-3 2', 'm5 19 3-2', 'm19 19-3-2'],
    code: ['m16 18 6-6-6-6', 'm8 6-6 6 6 6'],
    image: [{ r: [3, 3, 18, 18, 2] }, { c: [9, 9, 2] }, 'm21 15-3.1-3.1a2 2 0 0 0-2.8 0L6 21'],
    flag: ['M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z', 'M4 22v-7'],
    chevron: ['m9 18 6-6-6-6'],
    grid: [{ r: [3, 3, 7, 7, 1.5] }, { r: [14, 3, 7, 7, 1.5] }, { r: [3, 14, 7, 7, 1.5] }, { r: [14, 14, 7, 7, 1.5] }],
    list: ['M8 6h13', 'M8 12h13', 'M8 18h13', 'M3 6h.01', 'M3 12h.01', 'M3 18h.01'],
    users: ['M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2', { c: [9, 7, 4] }],
    layers: ['m12 2 10 5-10 5L2 7z', 'm2 17 10 5 10-5'],
    target: [{ c: [12, 12, 10] }, { c: [12, 12, 6] }, { c: [12, 12, 2] }],
    wallet: ['M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1', 'M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4'],
    team: ['M18 21a8 8 0 0 0-16 0', { c: [10, 8, 5] }, 'M22 20c0-3.37-2-6.5-4-8a5 5 0 0 0-.45-8.3'],
    honeycomb: ['M12 2 20 6.5v9L12 20l-8-4.5v-9z', 'M12 7.5 16 9.75v4.5L12 16.5l-4-2.25v-4.5z'],
    file: ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z', 'M14 2v6h6'],
    eye: ['M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7z', { c: [12, 12, 3] }],
  };
  const SVG = 'http://www.w3.org/2000/svg';
  P.icon = (name, cls) => {
    const svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('class', 'i' + (cls ? ' ' + cls : ''));
    for (const part of ICONS[name] || []) {
      let node;
      if (typeof part === 'string') { node = document.createElementNS(SVG, 'path'); node.setAttribute('d', part); }
      else if (part.c) { node = document.createElementNS(SVG, 'circle'); [['cx', 0], ['cy', 1], ['r', 2]].forEach(([k, i]) => node.setAttribute(k, part.c[i])); }
      else { node = document.createElementNS(SVG, 'rect'); [['x', 0], ['y', 1], ['width', 2], ['height', 3], ['rx', 4]].forEach(([k, i]) => node.setAttribute(k, part.r[i])); }
      svg.append(node);
    }
    return svg;
  };

  // ---------- labels (Azerbaijani) ----------
  P.L = {
    agentState: { WORKING: 'İşləyir', WAITING: 'Gözləyir', BLOCKED: 'Bloklanıb', FAILED: 'Xəta', IDLE: 'Boş', DONE: 'Bitib' },
    agentStates: ['WORKING', 'WAITING', 'BLOCKED', 'FAILED', 'IDLE', 'DONE'],
    stateVar: { WORKING: '--blue', WAITING: '--amber', BLOCKED: '--violet', FAILED: '--red', IDLE: '--slate', DONE: '--green' },
    role: { admin: 'Admin', manager: 'Menecer', employee: 'İşçi' },
    registration: { owner: 'şirkəti yaradır', invite: 'dəvətlə', company_code: 'şirkət kodu ilə' },
    timelineKind: { STEP: 'Addım', EVENT: 'Hadisə', RECEIPT: 'Qəbz', ASSERTION: 'Təsdiq', FINDING: 'Tapıntı', MESSAGE: 'Mesaj', DIALOG: 'Fikir' },
    findingClass: { BACKEND: 'Backend', DELIVERY_UI: 'Çatdırılma / UI', INVESTIGATE: 'Araşdırılmalı', FLAKY: 'Qeyri-sabit', AGENT_FAILURE: 'Agent xətası' },
    taskState: { PENDING: 'Növbədə', WAITING_EVENT: 'Hadisə gözləyir', RUNNING: 'İcra olunur', PASSED: 'Keçdi', FAILED: 'Uğursuz', BLOCKED: 'Bloklanıb', SKIPPED: 'Buraxıldı', LOST_RACE: 'Yarışı uduzdu' },
    taskStates: ['RUNNING', 'WAITING_EVENT', 'PASSED', 'FAILED', 'BLOCKED', 'LOST_RACE', 'SKIPPED', 'PENDING'],
    explorationStatus: { RUNNING: 'Gedir', FINISHED: 'Bitdi', TIMED_OUT: 'Vaxt bitdi', FAILED: 'Xəta', CANCELLED: 'Dayandırıldı' },
    phase: { ANONYMOUS: 'Anonim gəzinti', ROLE_BASED: 'Rollarla gəzinti', TRIAL_TOUCH: 'Sınaq toxunuşu' },
    phaseHelp: { ANONYMOUS: 'Girişsiz səhifələr, formlar, keçidlər', ROLE_BASED: 'Hər rolun gördüyü və görmədiyi', TRIAL_TOUCH: 'Hər yaratma formu bir dəfə' },
    phaseState: { PENDING: 'Növbədə', RUNNING: 'Gedir', DONE: 'Bitdi', SKIPPED: 'Buraxıldı' },
    provenance: { OBSERVED: 'Müşahidə', INFERRED: 'Ehtimal' },
    severity: { HIGH: 'Yüksək', MEDIUM: 'Orta', LOW: 'Aşağı' },
    exploreFinding: { BROKEN_LINK: 'Qırıq keçid', HTTP_ERROR: 'HTTP xətası', CONSOLE_ERROR: 'Konsol xətası', SLOW_PAGE: 'Yavaş səhifə', ACCESSIBILITY: 'Əlçatanlıq', UNEXPECTED_UI: 'Gözlənilməz UI' },
    pattern: { HAPPY_PATH: 'Uğurlu yol', PERMISSION: 'İcazə', RACE: 'Yarış', REALTIME: 'Real-time', BOUNDARY: 'Sərhəd', IDEMPOTENCY: 'Təkrar göndərmə' },
    actionKind: { REGISTER: 'Qeydiyyat', LOGIN: 'Giriş', CREATE: 'Yaratma', UPDATE: 'Yeniləmə', DELETE: 'Silmə', APPROVE: 'Təsdiq', REJECT: 'Rədd', ASSIGN: 'Təyin', SUBMIT: 'Göndərmə', NAVIGATE: 'Keçid', OTHER: 'Digər' },
    scenarioStatus: { DRAFT: 'Qaralama', APPROVED: 'Təsdiqlənib', FROZEN: 'Dondurulub', SUPERSEDED: 'Köhnəlib' },
    scenarioSource: { USER: 'İstifadəçi', EXPLORER: 'Kəşfiyyatçı', TRIAGE: 'Triaj' },
    triage: { SYSTEM_BUG: 'Sistem xətası', MODEL_GAP: 'Model boşluğu', SCENARIO_BUG: 'Ssenari xətası' },
    triageHelp: { SYSTEM_BUG: 'Hədəf sayt səhvdir — bildirilməlidir', MODEL_GAP: 'Saytı tanımağımız köhnəlib (axın, element dəyişib)', SCENARIO_BUG: 'Ssenarinin mətni və ya yoxlaması səhvdir' },
    surprise: { PROBLEM_REPORTED: 'Agent problem bildirdi', FAILED_STEP: 'Uğursuz addım', FINDING: 'Tapıntı' },
    result: { RUNNING: 'Gedir', PASSED: 'Keçdi', FAILED: 'Uğursuz', ABORTED: 'Dayandırıldı' },
    modelChange: { ADDED: 'Əlavə olunub', REMOVED: 'Silinib', CHANGED: 'Dəyişib' },
    subject: { PAGE: 'Səhifə', FORM: 'Form', FIELD: 'Sahə', ACTION: 'Əməliyyat' },
    limit: { MEMORY: 'yaddaş', CPU: 'prosessor' },
    transport: { websocket: 'WebSocket', sse: 'SSE', polling: 'Polling', unknown: 'Naməlum' },
    visitedAs: { anonymous: 'anonim', admin: 'admin', manager: 'menecer', employee: 'işçi' },
    activity: {
      STARTED: ['Başladı', 'blue'], PHASE_SKIPPED: ['Buraxıldı', 'amber'], NOTE: ['Qeyd', 'slate'], SESSIONS: ['Sessiya', 'cyan'],
      PHASE_STARTED: ['Faza', 'blue'], PAGE_VISITED: ['Səhifə', 'slate'], ACTION_DISCOVERED: ['Əməliyyat', 'cyan'], FINDING_RECORDED: ['Tapıntı', 'red'],
      UNKNOWN_RAISED: ['Sual', 'amber'], MODEL_UPDATED: ['Model', 'violet'], DRAFT_READY: ['Layihə', 'green'], FINISHED: ['Bitdi', 'green'], FAILED: ['Xəta', 'red'],
    },
  };

  // ---------- formatting ----------
  const pad = (n) => String(n).padStart(2, '0');
  const MONTHS = ['yan', 'fev', 'mar', 'apr', 'may', 'iyn', 'iyl', 'avq', 'sen', 'okt', 'noy', 'dek'];
  P.fmt = {
    duration(ms) {
      const s = Math.max(0, Math.floor((ms || 0) / 1000));
      const h = Math.floor(s / 3600);
      const m = Math.floor((s % 3600) / 60);
      return (h ? h + ':' + pad(m) : pad(m)) + ':' + pad(s % 60);
    },
    human(ms) {
      if (ms === null || ms === undefined) return '—';
      const s = Math.round(ms / 1000);
      if (s < 60) return s + ' san';
      const m = Math.floor(s / 60);
      if (m < 60) return m + ' dəq ' + (s % 60 ? (s % 60) + ' san' : '');
      return Math.floor(m / 60) + ' saat ' + (m % 60) + ' dəq';
    },
    clock(ms) { const d = new Date(ms); return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds()); },
    date(ms) { const d = new Date(ms); return d.getDate() + ' ' + MONTHS[d.getMonth()] + ', ' + pad(d.getHours()) + ':' + pad(d.getMinutes()); },
    ago(ms) {
      if (ms === null || ms === undefined) return '';
      const s = Math.max(0, Math.round((P.now() - ms) / 1000));
      if (s < 5) return 'indicə';
      if (s < 60) return s + ' san əvvəl';
      if (s < 3600) return Math.floor(s / 60) + ' dəq əvvəl';
      if (s < 86400) return Math.floor(s / 3600) + ' saat əvvəl';
      return Math.floor(s / 86400) + ' gün əvvəl';
    },
    latency(ms) {
      if (ms === null || ms === undefined) return '—';
      return ms < 1000 ? ms + ' ms' : (ms / 1000).toFixed(1).replace('.', ',') + ' s';
    },
    int(n) { return (n || 0).toLocaleString('az-Latn-AZ').replace(/ /g, ' '); },
    tokens(n) { if (!n) return '0'; return n >= 1e6 ? (n / 1e6).toFixed(1).replace('.', ',') + ' mln' : n >= 1e3 ? Math.round(n / 1e3) + ' min' : String(n); },
    usd(n) { return n === null || n === undefined ? '—' : '$' + n.toFixed(2); },
    host(url) { try { return new URL(url).host; } catch (e) { return url || ''; } },
    path(url) { try { const u = new URL(url); return u.pathname + u.search; } catch (e) { return url || ''; } },
    initials(name) {
      const parts = String(name || '?').trim().split(/\s+/).filter(Boolean);
      return ((parts[0] || '?')[0] + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toLocaleUpperCase('az');
    },
    hue(text) { let x = 0; for (const ch of String(text)) x = (x * 31 + ch.codePointAt(0)) % 360; return x; },
    mb(n) { return n >= 1024 ? (n / 1024).toFixed(1).replace('.', ',') + ' GB' : n + ' MB'; },
  };

  // ---------- server time ----------
  let serverAt = 0;
  let localAt = 0;
  /** The harness's time now, estimated from the last snapshot (so "x san əvvəl" never depends on the browser clock). */
  P.now = () => (serverAt ? serverAt + (performance.now() - localAt) : Date.now());
  P.syncTime = (generatedAtMs) => { if (generatedAtMs) { serverAt = generatedAtMs; localAt = performance.now(); } };

  // ---------- API ----------
  const tokenMeta = document.querySelector('meta[name="petek-token"]');
  const TOKEN = tokenMeta ? tokenMeta.getAttribute('content') : '';
  const targetMeta = document.querySelector('meta[name="petek-target"]');
  /** The site the instruction form starts with (the configured target); empty when the server names none. */
  P.defaultTarget = targetMeta ? targetMeta.getAttribute('content') || '' : '';
  async function request(method, path, body) {
    const init = { method, cache: 'no-store', headers: { Accept: 'application/json' } };
    if (method !== 'GET') {
      init.headers['X-Petek-Token'] = TOKEN;
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body || {});
    }
    let response;
    try { response = await fetch(path, init); } catch (e) { return { ok: false, status: 0, data: null, error: 'Serverlə əlaqə yoxdur.', problems: [] }; }
    let data = null;
    try { data = await response.json(); } catch (e) { data = null; }
    const failed = !response.ok;
    return {
      ok: !failed,
      status: response.status,
      data: failed ? null : data,
      error: failed ? (data && data.error) || 'Xəta (' + response.status + ').' : null,
      problems: failed && data && Array.isArray(data.problems) ? data.problems : [],
    };
  }
  P.api = { get: (path) => request('GET', path), post: (path, body) => request('POST', path, body) };

  // ---------- toasts ----------
  P.toast = (text, kind) => {
    const icon = kind === 'error' ? 'alert' : kind === 'ok' ? 'checkCircle' : 'sparkles';
    const el = P.h('div', 'toast ' + (kind || 'info'), P.icon(icon), P.h('div', { text }));
    P.$('toasts').append(el);
    setTimeout(() => el.remove(), kind === 'error' ? 7000 : 4500);
  };

  // ---------- live stream (one EventSource, topics per screen) ----------
  P.stream = (() => {
    const listeners = {};
    const EVENTS = ['snapshot', 'run', 'orchestrator', 'exploration', 'jobs'];
    let source = null;
    let key = '';
    let retry = 1000;
    let timer = null;
    function setConn(state, text) { P.$('conn').dataset.state = state; P.$('connText').textContent = text; }
    function emit(event, data) { for (const fn of listeners[event] || []) { try { fn(data); } catch (e) { console.error(event, e); } } }
    function connect() {
      clearTimeout(timer);
      if (source) source.close();
      setConn('connecting', 'Qoşulur…');
      const es = new EventSource('/api/stream?topics=' + encodeURIComponent(key));
      source = es;
      es.addEventListener('open', () => { retry = 1000; setConn('live', 'Canlı'); });
      for (const ev of EVENTS) {
        es.addEventListener(ev, (e) => { let data; try { data = JSON.parse(e.data); } catch (err) { return; } emit(ev, data); });
      }
      es.addEventListener('error', () => {
        if (es !== source) return;
        if (es.readyState === EventSource.CLOSED) {
          setConn('offline', 'Bağlantı yoxdur');
          timer = setTimeout(connect, retry);
          retry = Math.min(retry * 2, 15000);
        } else setConn('connecting', 'Yenidən qoşulur…');
      });
    }
    return {
      on(event, fn) { (listeners[event] = listeners[event] || new Set()).add(fn); },
      open(topics) {
        const next = [...new Set(topics)].sort().join(',');
        if (next === key && source && source.readyState !== EventSource.CLOSED) return;
        key = next;
        connect();
      },
    };
  })();

  // ---------- shared run state ----------
  P.run = { header: null, jobs: null };
  const runListeners = new Set();
  P.onRun = (fn) => runListeners.add(fn);
  function setHeader(header) {
    P.syncTime(header.generatedAtMs);
    P.run.header = header;
    for (const fn of runListeners) fn(header);
  }
  P.stream.on('snapshot', (s) => setHeader({ version: s.version, generatedAtMs: s.generatedAtMs, run: s.run, report: s.report, counters: s.counters }));
  P.stream.on('run', setHeader);
  P.stream.on('jobs', (j) => { P.run.jobs = j; for (const fn of runListeners) fn(P.run.header); });

  /** Live elapsed time of the shown run (frozen once it ended). */
  P.elapsed = (header) => {
    if (!header || !header.run) return 0;
    const live = header.run.phase === 'RUNNING';
    return header.run.elapsedMs + (live ? P.now() - header.generatedAtMs : 0);
  };
  /** [tone, text, live] of a run's status badge. */
  P.runStatus = (run) => {
    if (!run || !run.runId) return ['slate', 'Run yoxdur', false];
    if (run.phase === 'RUNNING') return ['blue', 'Davam edir', true];
    if (run.phase === 'FINISHED') {
      if (run.outcome === 'PASSED') return ['green', 'Keçdi', false];
      if (run.outcome === 'FAILED') return ['red', 'Uğursuz', false];
      return ['amber', 'Dayandırıldı', false];
    }
    if (run.phase === 'INTERRUPTED') return ['amber', 'Yarımçıq', false];
    return ['slate', 'Gözlənilir', false];
  };

  // ---------- ticking ----------
  const tickers = new Set();
  P.onTick = (fn) => tickers.add(fn);
  setInterval(() => { for (const fn of tickers) { try { fn(); } catch (e) { console.error(e); } } }, 1000);

  // ---------- shared pieces ----------
  P.badge = (tone, text, opts) => P.h('span', { class: 'badge tone-' + tone + (opts && opts.dot ? ' dot' : '') + (opts && opts.live ? ' live' : '') + (opts && opts.lg ? ' lg' : ''), text });
  P.tag = (cls, text, title) => P.h('span', { class: 'tag ' + cls, text, title });

  /** A card with a header (icon, title, subtitle, right-side actions) and a body. */
  P.card = (title, opts) => {
    const o = opts || {};
    const head = P.h('div', 'card-head');
    if (o.icon) head.append(P.h('span', 'icon-wrap', P.icon(o.icon, 'sm')));
    const titles = P.h('div', { style: { 'min-width': '0' } }, P.h('h2', { text: title }));
    if (o.sub !== undefined) titles.append(P.h('div', { class: 'sub', text: o.sub }));
    head.append(titles, P.h('span', 'spacer'));
    const actions = P.h('div', 'row wrap');
    head.append(actions);
    const body = P.h('div', o.flush ? 'card-body flush' : 'card-body');
    const el = P.h('section', { class: 'card' + (o.class ? ' ' + o.class : ''), attrs: { 'aria-label': title } }, head, body);
    return { el, head, body, actions, titles };
  };

  P.empty = (title, text, action) =>
    P.h('div', 'empty-state',
      P.icon('honeycomb', 'art'),
      P.h('div', null, P.h('strong', { text: title }), P.h('p', { text })),
      action || null);

  P.button = (text, opts) => {
    const o = opts || {};
    const b = P.h('button', { class: 'btn' + (o.kind ? ' ' + o.kind : ''), attrs: { type: 'button', title: o.title } });
    if (o.icon) b.append(P.icon(o.icon, 'sm'));
    b.append(P.h('span', { text }));
    if (o.on) b.addEventListener('click', o.on);
    return b;
  };

  /** Runs [work] with the button disabled; shows the error as a toast. */
  P.busy = async (button, work) => {
    if (button) button.disabled = true;
    try { return await work(); } finally { if (button) button.disabled = false; }
  };

  /** A read-only YAML view with line numbers and light highlighting (keys, strings, comments). */
  P.code = (text) => {
    const pre = P.h('pre', 'code');
    for (const line of String(text || '').split('\n')) {
      const row = P.h('span', 'ln');
      const content = P.h('span');
      const comment = line.match(/^(\s*)(#.*)$/);
      const pair = line.match(/^(\s*-?\s*)([A-Za-z0-9_.-]+)(:)(.*)$/);
      if (comment) P.append(content, comment[1], P.h('span', { class: 'c', text: comment[2] }));
      else if (pair) {
        const rest = pair[4];
        const quoted = rest.match(/^(\s*)(["'].*)$/);
        P.append(content, pair[1], P.h('span', { class: 'k', text: pair[2] }), pair[3]);
        if (quoted) P.append(content, quoted[1], P.h('span', { class: 's', text: quoted[2] }));
        else P.append(content, rest);
      } else content.textContent = line;
      row.append(content);
      pre.append(row);
    }
    return pre;
  };

  /** The run header shared by the agents and orchestrator screens. */
  P.runBanner = () => {
    const title = P.h('h2', { text: 'Run gözlənilir' });
    const target = P.h('span');
    const runId = P.h('span', 'mono');
    const started = P.h('span');
    const step = P.h('span', { class: 'value mono', text: '—' });
    const elapsed = P.h('span', { class: 'value big mono', text: '00:00' });
    const badgeBox = P.h('span');
    const report = P.h('a', { class: 'btn small', attrs: { href: '/report/', target: '_blank', rel: 'noopener' }, hidden: true }, P.icon('file', 'sm'), P.h('span', { text: 'Hesabat' }));
    const el = P.h('section', { class: 'card run-banner', attrs: { 'aria-label': 'Run' } },
      P.h('div', 'who', title, P.h('div', 'run-meta', target, runId, started)),
      P.h('div', 'stat', P.h('span', { class: 'label', text: 'Cari addım' }), step),
      P.h('div', 'stat', P.h('span', { class: 'label', text: 'Müddət' }), elapsed),
      badgeBox, report);
    let header = null;
    function update(h) {
      header = h;
      const run = h && h.run;
      title.textContent = run && run.campaignName ? run.campaignName : run && run.runId ? 'Kampaniya' : 'Aktiv run yoxdur';
      P.fill(target, run && run.target ? [P.icon('globe', 'sm'), P.fmt.host(run.target)] : []);
      runId.textContent = run && run.runId ? run.runId : '';
      P.fill(started, run && run.startedAtMs ? [P.icon('clock', 'sm'), 'başladı ' + P.fmt.clock(run.startedAtMs)] : []);
      step.textContent = (run && run.step) || '—';
      step.title = (run && run.step) || '';
      const [tone, text, live] = P.runStatus(run);
      P.fill(badgeBox, P.badge(tone, text, { dot: true, live, lg: true }));
      report.hidden = !(h && h.report && h.report.ready);
      tick();
    }
    function tick() { elapsed.textContent = P.fmt.duration(P.elapsed(header)); }
    P.onTick(tick);
    return { el, update };
  };

  // ---------- screens and router ----------
  const screens = [];
  let current = null;
  /** def: {id, title, subtitle, icon, group, topics, mount(el), show(params), hide(), badge(): {text, tone}|null} */
  P.register = (def) => screens.push(def);
  P.screens = () => screens;
  P.current = () => current;
  P.go = (id, params) => {
    const query = params ? '?' + new URLSearchParams(params).toString() : '';
    const next = '#/' + id + query;
    if (location.hash === next) route(); else location.hash = next;
  };
  function parse() {
    const raw = location.hash.replace(/^#\/?/, '');
    const [id, query] = raw.split('?');
    return { id, params: Object.fromEntries(new URLSearchParams(query || '')) };
  }
  function route() {
    const { id, params } = parse();
    const def = screens.find((s) => s.id === id);
    if (!def) { P.go(P.defaultScreen ? P.defaultScreen() : screens[0].id); return; }
    if (current && current !== def) { current.el.hidden = true; if (current.hide) current.hide(); }
    const fresh = current !== def;
    current = def;
    if (!def.el) { def.el = P.h('section', { class: 'screen', attrs: { 'aria-label': def.title } }); P.$('screens').append(def.el); def.mount(def.el); }
    def.el.hidden = false;
    P.$('screenTitle').textContent = def.title;
    P.$('screenSubtitle').textContent = def.subtitle;
    document.title = def.title + ' — Pətək';
    if (P.onRoute) P.onRoute(def);
    P.stream.open(def.topics);
    if (def.show) def.show(params || {}, fresh);
    if (fresh) window.scrollTo(0, 0);
  }
  P.startRouter = () => { window.addEventListener('hashchange', route); route(); };
})();
