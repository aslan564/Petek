package az.petek.faketarget.web

/** Inline CSS and scripts (the fake serves no external assets). */
internal object Assets {
    const val STYLES = """
*{box-sizing:border-box}
body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;color:#1f2933;background:#f5f7fa;line-height:1.5}
header.top{display:flex;flex-wrap:wrap;gap:16px;align-items:center;padding:12px 24px;background:#1e3a5f;color:#fff}
header.top a{color:#fff}
.brand{font-weight:700;font-size:1.2rem;text-decoration:none}
nav ul{display:flex;gap:16px;list-style:none;margin:0;padding:0}
.user{margin-left:auto;display:flex;gap:10px;align-items:center}
.user form{margin:0}
.role{font-size:.8rem;background:rgba(255,255,255,.15);padding:2px 8px;border-radius:999px}
.layout{display:grid;grid-template-columns:minmax(0,1fr) 300px;gap:24px;max-width:1200px;margin:24px auto;padding:0 24px}
.layout.single{grid-template-columns:minmax(0,560px);justify-content:center}
main,aside.notifications{background:#fff;border-radius:8px;padding:24px;box-shadow:0 1px 3px rgba(0,0,0,.12)}
aside.notifications{align-self:start;padding:16px}
aside.notifications h2{font-size:1rem;margin:0 0 8px}
aside.notifications ul{list-style:none;margin:0;padding:0}
aside.notifications li{padding:6px 0;border-bottom:1px solid #e4e7eb}
.unread a{font-weight:700}
.empty{color:#7b8794}
.badge{display:inline-block;min-width:1.6em;text-align:center;background:#dc2626;color:#fff;border-radius:999px;padding:0 6px;font-size:.8rem}
.field{display:flex;flex-direction:column;margin-bottom:12px;max-width:440px}
label{font-weight:600;margin-bottom:4px}
input,select,textarea{padding:8px;border:1px solid #cbd2d9;border-radius:4px;font:inherit}
button{padding:8px 16px;border:0;border-radius:4px;background:#2563eb;color:#fff;font:inherit;cursor:pointer}
button.secondary{background:#52606d}
button.danger{background:#dc2626}
.error{color:#991b1b;background:#fef2f2;padding:8px 12px;border-radius:4px}
.info{color:#065f46;background:#ecfdf5;padding:8px 12px;border-radius:4px}
.status{font-family:ui-monospace,monospace;padding:2px 8px;border-radius:4px;background:#e4e7eb}
.actions{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;margin:16px 0}
.actions form{display:flex;gap:8px;align-items:flex-end;margin:0}
.actions .field{margin:0}
article{border-bottom:1px solid #e4e7eb;padding:12px 0}
table{border-collapse:collapse;width:100%}
th,td{text-align:left;padding:6px 8px;border-bottom:1px solid #e4e7eb}
@media (max-width:800px){.layout{grid-template-columns:minmax(0,1fr)}}
"""

    /**
     * Live notifications over SSE: resumes after the newest notification the page rendered (`data-last-id`), prepends
     * each new `notification-item` and bumps `notification-count`. EventSource reconnects on its own and then sends
     * `Last-Event-ID`, which the server prefers over `?after=`.
     */
    const val LIVE_NOTIFICATIONS = """
(function () {
  var list = document.querySelector('[data-testid="notification-list"]');
  var count = document.querySelector('[data-testid="notification-count"]');
  if (!list || !count || !window.EventSource) return;
  var after = list.getAttribute('data-last-id');
  var source = new EventSource('/events' + (after ? '?after=' + encodeURIComponent(after) : ''));
  source.addEventListener('notification', function (event) {
    var n = JSON.parse(event.data);
    if (list.querySelector('[data-notification-id="' + n.id + '"]')) return;
    var empty = list.querySelector('.empty');
    if (empty) empty.parentNode.removeChild(empty);
    var item = document.createElement('li');
    item.className = 'unread';
    item.setAttribute('data-testid', 'notification-item');
    item.setAttribute('data-id', n.object_id);
    item.setAttribute('data-notification-id', n.id);
    var link = document.createElement('a');
    link.href = n.link;
    link.textContent = n.text;
    item.appendChild(link);
    list.insertBefore(item, list.firstChild);
    list.setAttribute('data-last-id', n.id);
    count.textContent = String((parseInt(count.textContent, 10) || 0) + 1);
  });
  window.addEventListener('pagehide', function () { source.close(); });
})();
"""

    /** Fills the department select of `/join` as soon as a known company code is typed. */
    const val JOIN_DEPARTMENTS = """
(function () {
  var code = document.getElementById('join-company-code');
  var select = document.getElementById('join-department');
  if (!code || !select || !window.fetch) return;
  var loaded = null;
  function load() {
    var value = code.value.trim();
    if (!value || value === loaded) return;
    loaded = value;
    fetch('/join/departments?code=' + encodeURIComponent(value), { headers: { 'Accept': 'application/json' } })
      .then(function (response) { return response.ok ? response.json() : { departments: [] }; })
      .then(function (data) {
        if (code.value.trim() !== value) return;
        var current = select.value;
        while (select.options.length > 1) select.remove(1);
        data.departments.forEach(function (department) {
          var option = document.createElement('option');
          option.value = department.name;
          option.textContent = department.name;
          select.appendChild(option);
        });
        if (current) select.value = current;
      })
      .catch(function () { loaded = null; });
  }
  code.addEventListener('input', load);
  code.addEventListener('change', load);
  load();
})();
"""
}
