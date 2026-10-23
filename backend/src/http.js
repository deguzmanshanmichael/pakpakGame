// Minimal router and request helpers on top of node:http (no third-party dependencies).
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';

export class HttpError extends Error {
  constructor(status, code, message, extra) {
    super(message ?? code);
    this.status = status;
    this.code = code;
    this.extra = extra;
  }
}
export const bad = (code, message, extra) => new HttpError(400, code, message, extra);
export const forbidden = (message = 'Not allowed') => new HttpError(403, 'forbidden', message);
export const notFound = (message = 'Not found') => new HttpError(404, 'not_found', message);
export const conflict = (code, message) => new HttpError(409, code, message);

const MAX_BODY = 64 * 1024;

export class Router {
  constructor() { this.routes = []; }
  add(method, path, auth, handler) {
    const keys = [];
    const re = new RegExp('^' + path.replace(/:(\w+)/g, (_, k) => { keys.push(k); return '([^/]+)'; }) + '/?$');
    this.routes.push({ method, re, keys, auth, handler });
  }
  get(p, a, h) { this.add('GET', p, a, h); }
  post(p, a, h) { this.add('POST', p, a, h); }
  put(p, a, h) { this.add('PUT', p, a, h); }
  patch(p, a, h) { this.add('PATCH', p, a, h); }
  delete(p, a, h) { this.add('DELETE', p, a, h); }
  match(method, path) {
    let pathMatched = false;
    for (const r of this.routes) {
      const m = r.re.exec(path);
      if (!m) continue;
      pathMatched = true;
      if (r.method !== method) continue;
      const params = {};
      r.keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); });
      return { route: r, params };
    }
    return pathMatched ? { methodNotAllowed: true } : null;
  }
}

export function readJson(req) {
  return new Promise((resolve, reject) => {
    if (req.method === 'GET' || req.method === 'HEAD') return resolve({});
    const type = req.headers['content-type'] ?? '';
    const tooLarge = () => new HttpError(413, 'too_large', 'Request body too large');
    if (Number(req.headers['content-length'] ?? 0) > MAX_BODY) { req.resume(); return reject(tooLarge()); }
    let size = 0;
    let overflow = false;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) { overflow = true; chunks.length = 0; return; }
      chunks.push(c);
    });
    req.on('end', () => {
      if (overflow) return reject(tooLarge());
      if (size === 0) return resolve({});
      if (!type.includes('application/json')) return reject(new HttpError(415, 'unsupported_media', 'Expected application/json'));
      try {
        const v = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        if (v === null || typeof v !== 'object' || Array.isArray(v)) throw new Error();
        resolve(v);
      } catch { reject(bad('invalid_json', 'Malformed JSON body')); }
    });
    req.on('error', reject);
  });
}

export const SECURITY_HEADERS = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Cache-Control': 'no-store',
};

// A Promise that slipped into a response (a missing `await`) would serialize as {} and silently
// drop data. Refuse to send it so the bug surfaces as a 500 (and fails the tests) instead.
const noPromises = (key, value) => {
  if (value && typeof value === 'object' && typeof value.then === 'function') throw new Error(`unresolved Promise in response field "${key}"`);
  return value;
};

export function sendJson(res, status, body) {
  const data = JSON.stringify(body, noPromises);
  res.writeHead(status, { ...SECURITY_HEADERS, 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(data) });
  res.end(data);
}

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.png': 'image/png', '.svg': 'image/svg+xml' };

export async function serveStatic(res, rootDir, relPath) {
  const safe = normalize(relPath).replace(/^(\.\.[/\\])+/, '');
  const file = join(rootDir, safe);
  if (!file.startsWith(rootDir)) return false;
  try {
    const data = await readFile(file);
    res.writeHead(200, {
      ...SECURITY_HEADERS,
      'Content-Type': MIME[extname(file)] ?? 'application/octet-stream',
      'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'",
    });
    res.end(data);
    return true;
  } catch {
    return false;
  }
}

// Input validation helpers. They throw 400s with field-specific codes.
export const v = {
  str(val, field, { min = 0, max = 200, pattern, optional = false } = {}) {
    if (val === undefined || val === null) {
      if (optional) return undefined;
      throw bad('invalid_' + field, `${field} is required`);
    }
    if (typeof val !== 'string') throw bad('invalid_' + field, `${field} must be text`);
    const s = val.trim();
    if (s.length < min || s.length > max) throw bad('invalid_' + field, `${field} must be ${min}-${max} characters`);
    if (pattern && !pattern.test(s)) throw bad('invalid_' + field, `${field} has invalid characters`);
    return s;
  },
  int(val, field, { min = 0, max = Number.MAX_SAFE_INTEGER, optional = false } = {}) {
    if (val === undefined || val === null) {
      if (optional) return undefined;
      throw bad('invalid_' + field, `${field} is required`);
    }
    if (!Number.isInteger(val) || val < min || val > max) throw bad('invalid_' + field, `${field} must be an integer between ${min} and ${max}`);
    return val;
  },
  oneOf(val, field, options, { optional = false } = {}) {
    if (val === undefined && optional) return undefined;
    if (!options.includes(val)) throw bad('invalid_' + field, `${field} must be one of ${options.join(', ')}`);
    return val;
  },
  id(val, field) {
    const n = typeof val === 'string' ? Number(val) : val;
    if (!Number.isInteger(n) || n <= 0) throw bad('invalid_' + field, `${field} must be a positive id`);
    return n;
  },
};

/** Sequential async map/filter (sequential keeps statements in order inside a transaction). */
export async function mapSeq(arr, fn) {
  const out = [];
  for (let i = 0; i < arr.length; i++) out.push(await fn(arr[i], i));
  return out;
}
export async function filterSeq(arr, fn) {
  const out = [];
  for (let i = 0; i < arr.length; i++) if (await fn(arr[i], i)) out.push(arr[i]);
  return out;
}
