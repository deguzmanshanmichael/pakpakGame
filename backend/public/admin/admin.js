// PakPak admin panel. Builds DOM with textContent only (player-supplied text is never parsed as HTML).
'use strict';

const $ = (sel) => document.querySelector(sel);
let token = sessionStorage.getItem('pakpak_admin_token');

function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, val] of Object.entries(attrs)) {
    if (k === 'on') for (const [ev, fn] of Object.entries(val)) el.addEventListener(ev, fn);
    else if (k === 'class') el.className = val;
    else if (k === 'checked' || k === 'value' || k === 'disabled') el[k] = val;
    else el.setAttribute(k, val);
  }
  for (const c of children.flat()) if (c != null && c !== false) el.append(c instanceof Node ? c : String(c));
  return el;
}
const fmt = (ts) => (ts ? new Date(ts).toLocaleString() : '-');

async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) { logout(); throw new Error('Session expired, sign in again'); }
  if (!res.ok) throw new Error(data.message || `HTTP ${res.status}`);
  return data;
}

function showError(e) { $('#app-error').textContent = e ? e.message || String(e) : ''; }
function table(headers, rows) {
  return h('div', { class: 'table-wrap' }, h('table', {}, h('thead', {}, h('tr', {}, headers.map((x) => h('th', {}, x)))), h('tbody', {}, rows)));
}
function askReason(label) {
  const r = prompt(label);
  if (r === null) return null;
  if (r.trim().length < 3) { alert('Please enter a reason (at least 3 characters).'); return null; }
  return r.trim();
}

const tabs = {
  async overview() {
    const o = await api('GET', '/api/admin/overview');
    const stat = (label, val) => h('div', { class: 'card' }, h('div', { class: 'muted' }, label), h('div', { class: 'stat' }, val));
    return h('div', {},
      h('div', { class: 'grid' },
        stat('Players', o.users), stat('Active today', o.activeToday), stat('Runs today', o.runsToday),
        stat('Open reports', o.openReports), stat('Flagged trades', o.flaggedTrades), stat('Flagged runs (7d)', o.flaggedRuns),
        stat('Pending claims', o.pendingRedemptions)),
      h('div', { class: 'card' }, h('h3', {}, 'Copies issued by rarity'),
        table(['Rarity', 'Issued'], o.issuedByRarity.map((r) => h('tr', {}, h('td', {}, r.rarity), h('td', {}, r.issued))))));
  },

  async redemptions() {
    const wrap = h('div');
    const sel = h('select', {}, ['submitted', 'verified', 'rejected', 'fulfilled'].map((s) => h('option', { value: s }, s)));
    const list = h('div');
    const load = async () => {
      const { redemptions } = await api('GET', `/api/admin/redemptions?status=${sel.value}`);
      list.replaceChildren(redemptions.length ? '' : h('p', { class: 'muted' }, 'No claims with this status.'), ...redemptions.map((r) => {
        const decide = (status) => async () => {
          const reason = askReason(`Reason for marking claim #${r.id} as ${status}:`);
          if (!reason) return;
          try { await api('POST', `/api/admin/redemptions/${r.id}/decision`, { status, reason }); load(); } catch (e) { showError(e); }
        };
        const complete = r.current.verifiedDistinct >= r.current.total;
        return h('div', { class: 'card' },
          h('div', { class: 'row' }, h('strong', {}, `#${r.id} ${r.username} (${r.player_code})`), h('span', { class: 'pill' }, r.status)),
          h('p', {}, 'Contact handle: ', h('strong', {}, r.contact_handle)),
          r.note ? h('p', {}, 'Note: ', r.note) : null,
          h('p', { class: complete ? 'ok' : 'error' }, `Verified collection now: ${r.current.verifiedDistinct}/${r.current.total} (at submit: ${r.snapshot.verifiedDistinct}/${r.snapshot.total})`),
          h('p', { class: r.flagged ? 'error' : 'muted' }, `Anti-cheat: ${r.flagged ? 'FLAGGED' : 'not flagged'}, suspicious runs: ${r.suspicious_runs}`),
          h('p', { class: 'muted' }, `Submitted ${fmt(r.created_at)}${r.decided_at ? `, decided ${fmt(r.decided_at)}: ${r.decision_reason}` : ''}`),
          h('div', { class: 'row' },
            r.status === 'submitted' ? h('button', { class: 'primary', on: { click: decide('verified') } }, 'Verify (eligible)') : null,
            r.status === 'verified' ? h('button', { class: 'primary', on: { click: decide('fulfilled') } }, 'Record prize as fulfilled') : null,
            ['submitted', 'verified'].includes(r.status) ? h('button', { class: 'danger', on: { click: decide('rejected') } }, 'Reject') : null));
      }));
    };
    sel.addEventListener('change', load);
    wrap.append(h('p', { class: 'muted' }, 'Verifying a claim only records that the account qualifies. The prize itself is handled manually by Shan; record it as fulfilled afterwards.'),
      h('label', {}, 'Status ', sel), list);
    await load();
    return wrap;
  },

  async reports() {
    const { reports } = await api('GET', '/api/admin/reports?status=open');
    if (!reports.length) return h('p', { class: 'muted' }, 'No open reports.');
    return h('div', {}, reports.map((r) => {
      const act = (status) => async () => {
        const resolution = askReason(`Resolution note (${status}):`);
        if (!resolution) return;
        try { await api('POST', `/api/admin/reports/${r.id}/resolve`, { status, resolution }); render('reports'); } catch (e) { showError(e); }
      };
      return h('div', { class: 'card' },
        h('div', { class: 'row' }, h('strong', {}, `#${r.id} ${r.category}`), h('span', { class: 'muted' }, `${r.reporter} reported ${r.target ?? '?'} on ${fmt(r.created_at)}`)),
        r.message_body ? h('p', {}, 'Message: ', h('em', {}, r.message_body)) : null,
        r.details ? h('p', {}, 'Details: ', r.details) : null,
        h('div', { class: 'row' },
          h('button', { class: 'primary', on: { click: act('resolved') } }, 'Resolve'),
          h('button', { on: { click: act('dismissed') } }, 'Dismiss'),
          h('button', { class: 'danger', on: { click: () => suspend(r.target_user_id) } }, 'Suspend reported user')));
    }));
  },

  async trades() {
    const { trades } = await api('GET', '/api/admin/trades/flagged');
    if (!trades.length) return h('p', { class: 'muted' }, 'No unreviewed flagged trades.');
    return table(['Trade', 'Parties', 'Items', 'Flags', 'Action'], trades.map((t) => h('tr', {},
      h('td', {}, `#${t.id}`, h('br'), fmt(t.completed_at)),
      h('td', {}, `${t.proposer} → ${t.recipient}`),
      h('td', {}, h('pre', {}, t.items.map((i) => `${i.side}: ${i.qty}x ${i.collectible_id}`).join('\n'))),
      h('td', {}, t.flag_reason),
      h('td', {}, h('button', { on: { click: async () => {
        const note = askReason('Review note:');
        if (!note) return;
        try { await api('POST', `/api/admin/trades/${t.id}/review`, { note }); render('trades'); } catch (e) { showError(e); }
      } } }, 'Mark reviewed')))));
  },

  async runs() {
    const { runs } = await api('GET', '/api/admin/runs/flagged');
    if (!runs.length) return h('p', { class: 'muted' }, 'No flagged runs.');
    return table(['When', 'Player', 'Mode', 'Score', 'Pipes', 'Duration', 'Reason'], runs.map((r) => h('tr', {},
      h('td', {}, fmt(r.received_at)), h('td', {}, r.username), h('td', {}, r.mode + (r.level_id ? ` ${r.level_id}` : '')),
      h('td', {}, r.score), h('td', {}, r.pipes), h('td', {}, `${(r.duration_ms / 1000).toFixed(1)}s`), h('td', {}, r.flag_reason))));
  },

  async collectibles() {
    const { items } = await api('GET', '/api/admin/collectibles');
    const save = (it, row) => async () => {
      const q = (n) => row.querySelector(`[name=${n}]`);
      const ms = q('maxSupply').value.trim();
      try {
        await api('PATCH', `/api/admin/collectibles/${it.id}`, {
          name: q('name').value, description: q('description').value, tradeable: q('tradeable').checked,
          inDropPool: q('inDropPool').checked, enabled: q('enabled').checked, maxSupply: ms === '' ? null : Number(ms),
        });
        row.querySelector('.saved').textContent = 'saved';
      } catch (e) { showError(e); }
    };
    return h('div', {},
      h('p', { class: 'muted' }, 'Max supply caps how many copies can ever exist (blank = unlimited). Set it to 1 for a globally unique item. Drop pool items appear in capsules; others are earned through milestones or adventure mastery.'),
      table(['ID', 'Rarity', 'Name', 'Description', 'Trade', 'Pool', 'On', 'Max supply', 'Issued', ''], items.map((it) => {
        const row = h('tr', {},
          h('td', {}, it.id), h('td', {}, it.rarity),
          h('td', {}, h('input', { name: 'name', value: it.name })),
          h('td', {}, h('input', { name: 'description', value: it.description })),
          h('td', {}, h('input', { name: 'tradeable', type: 'checkbox', checked: it.tradeable })),
          h('td', {}, h('input', { name: 'inDropPool', type: 'checkbox', checked: it.inDropPool })),
          h('td', {}, h('input', { name: 'enabled', type: 'checkbox', checked: it.enabled })),
          h('td', {}, h('input', { name: 'maxSupply', type: 'number', min: '0', value: it.maxSupply ?? '' })),
          h('td', {}, it.issued),
          h('td', {}));
        row.lastChild.append(h('button', { on: { click: save(it, row) } }, 'Save'), h('span', { class: 'saved ok' }));
        return row;
      })));
  },

  async config() {
    const { config, overridden } = await api('GET', '/api/admin/config');
    const sections = ['drops', 'economy', 'medals', 'antiCheat', 'skins', 'achievements'];
    return h('div', {},
      h('p', { class: 'muted' }, 'Edit a section as JSON and save. Changes apply immediately to new runs and are pushed to clients on their next config refresh. Drop weights are relative (out of their sum).'),
      sections.map((key) => {
        const ta = h('textarea', {}, JSON.stringify(config[key], null, 2));
        const msg = h('span', { class: 'ok' });
        const ov = overridden.find((o) => o.key === key);
        let preview = null;
        if (key === 'drops') {
          const w = config.drops.weights;
          const total = Object.values(w).reduce((a, b) => a + b, 0);
          preview = h('p', { class: 'muted' }, 'Current per-capsule odds: ', Object.entries(w).map(([r, x]) => `${r} ${(100 * x / total).toFixed(2)}%`).join(', '));
        }
        return h('div', { class: 'card' },
          h('h3', {}, key, ov ? h('span', { class: 'pill' }, `overridden ${fmt(ov.updated_at)}`) : h('span', { class: 'pill' }, 'default')),
          preview, ta,
          h('div', { class: 'row' },
            h('button', { class: 'primary', on: { click: async () => {
              let value;
              try { value = JSON.parse(ta.value); } catch { showError(new Error(`${key}: invalid JSON`)); return; }
              try { await api('PUT', `/api/admin/config/${key}`, { value }); msg.textContent = 'saved'; showError(null); } catch (e) { showError(e); }
            } } }, 'Save'),
            h('button', { on: { click: async () => {
              if (!confirm(`Reset ${key} to the built-in defaults?`)) return;
              try { await api('PUT', `/api/admin/config/${key}`, { reset: true }); render('config'); } catch (e) { showError(e); }
            } } }, 'Reset to default'), msg));
      }));
  },

  async notice() {
    const { config } = await api('GET', '/api/admin/config');
    const n = config.notice;
    const fields = {};
    const field = (k, label, big) => { fields[k] = h(big ? 'textarea' : 'input', big ? {} : { value: n[k] }, big ? n[k] : null); return h('label', {}, label, fields[k]); };
    const msg = h('span', { class: 'ok' });
    return h('div', { class: 'card' },
      h('p', { class: 'muted' }, 'Shown in the app under Collectibles and on the completion screen. Keep the redemption rules clear: the prize is manual and nothing is approved until you verify it here.'),
      field('title', 'Title'), field('body', 'Body (Taglish notice)', true), field('eligibility', 'Eligibility', true),
      field('process', 'Verification process', true), field('contact', 'Contact / redemption instructions'),
      h('div', { class: 'row' }, h('button', { class: 'primary', on: { click: async () => {
        const value = Object.fromEntries(Object.entries(fields).map(([k, el]) => [k, el.value]));
        try { await api('PUT', '/api/admin/config/notice', { value }); msg.textContent = 'saved'; } catch (e) { showError(e); }
      } } }, 'Save notice'), msg));
  },

  async users() {
    const q = h('input', { placeholder: 'username or PK-XXXXXX' });
    const list = h('div');
    const load = async () => {
      const { users } = await api('GET', `/api/admin/users?q=${encodeURIComponent(q.value)}`);
      list.replaceChildren(table(['User', 'Role', 'Status', 'Coins', 'Best (verified)', 'Flags', 'Actions'], users.map((u) => h('tr', {},
        h('td', {}, `${u.username} (${u.player_code})`, h('br'), h('span', { class: 'muted' }, `joined ${fmt(u.created_at)}`)),
        h('td', {}, u.role), h('td', {}, u.status), h('td', {}, u.coins), h('td', {}, `${u.best_score} (${u.best_score_verified})`),
        h('td', {}, u.flagged ? 'FLAGGED ' : '', `${u.suspicious_runs} suspicious`),
        h('td', {}, h('div', { class: 'row' },
          u.status === 'active'
            ? h('button', { class: 'danger', on: { click: () => suspend(u.id).then(load) } }, 'Suspend')
            : h('button', { on: { click: () => setStatus(u.id, 'active').then(load) } }, 'Reactivate'),
          h('button', { on: { click: async () => {
            const reason = askReason(u.flagged ? 'Reason for clearing the flag:' : 'Reason for flagging:');
            if (!reason) return;
            try { await api('POST', `/api/admin/users/${u.id}/flag`, { flagged: !u.flagged, reason }); load(); } catch (e) { showError(e); }
          } } }, u.flagged ? 'Clear flag' : 'Flag')))))));
    };
    q.addEventListener('change', load);
    await load();
    return h('div', {}, h('label', {}, 'Search ', q), list);
  },

  async audit() {
    const { entries } = await api('GET', '/api/admin/audit?limit=200');
    return table(['When', 'Actor', 'Action', 'Target', 'Details'], entries.map((e) => h('tr', {},
      h('td', {}, fmt(e.created_at)), h('td', {}, e.actor ?? 'system'), h('td', {}, e.action),
      h('td', {}, e.target_type ? `${e.target_type} ${e.target_id}` : ''), h('td', {}, h('pre', {}, e.details ? JSON.stringify(e.details) : '')))));
  },
};

async function setStatus(id, status) {
  const reason = askReason(`Reason for setting user ${id} to ${status}:`);
  if (!reason) return;
  try { await api('POST', `/api/admin/users/${id}/status`, { status, reason }); } catch (e) { showError(e); }
}
const suspend = (id) => setStatus(id, 'suspended');

let current = 'overview';
async function render(tab = current) {
  current = tab;
  document.querySelectorAll('#tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
  showError(null);
  try { $('#content').replaceChildren(await tabs[tab]()); } catch (e) { showError(e); }
}

function logout() {
  if (token) fetch('/api/auth/logout', { method: 'POST', headers: { Authorization: `Bearer ${token}` } }).catch(() => {});
  token = null;
  sessionStorage.removeItem('pakpak_admin_token');
  $('#app-view').hidden = true; $('#login-view').hidden = false; $('#logout').hidden = true; $('#who').textContent = '';
}

async function enter() {
  try {
    const st = await api('GET', '/api/me/state');
    if (st.user.role !== 'admin') { logout(); $('#login-error').textContent = 'This account is not an admin.'; return; }
    $('#who').textContent = `Signed in as ${st.user.username}`;
    $('#login-view').hidden = true; $('#app-view').hidden = false; $('#logout').hidden = false;
    render('overview');
  } catch (e) { $('#login-error').textContent = e.message; }
}

$('#login-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const f = new FormData(ev.target);
  $('#login-error').textContent = '';
  try {
    const res = await api('POST', '/api/auth/login', { username: f.get('username'), password: f.get('password') });
    token = res.token;
    sessionStorage.setItem('pakpak_admin_token', token);
    enter();
  } catch (e) { $('#login-error').textContent = e.message; }
});
$('#logout').addEventListener('click', logout);
$('#tabs').addEventListener('click', (ev) => { if (ev.target.dataset.tab) render(ev.target.dataset.tab); });
if (token) enter();
