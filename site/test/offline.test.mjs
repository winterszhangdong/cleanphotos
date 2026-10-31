import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import worker, { preferred } from '../src/worker.js';

const siteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = path.resolve(siteRoot, '..');

test('preferred uses the highest-q tag', () => {
  assert.equal(preferred(null), 'zh');
  assert.equal(preferred(''), 'zh');
  assert.equal(preferred('fr-FR,zh-CN;q=0.9'), 'en');
  assert.equal(preferred('fr-FR;q=0.2,zh-CN;q=0.9'), 'zh');
  assert.equal(preferred('zh-CN,en-US;q=0.8'), 'zh');
  assert.equal(preferred('en-GB,zh;q=0.9'), 'en');
  assert.equal(preferred('de-DE'), 'en');
  assert.equal(preferred('zh-Hant'), 'zh');
  assert.equal(preferred('zh;q=0,en;q=0.2'), 'en');
  assert.equal(preferred('zh;q=nope,en;q=0.4'), 'en');
  assert.equal(preferred('*;q=0.8,zh;q=0.2'), 'en');
  assert.equal(preferred('zh;q=0'), 'zh');
});

function assetEnv() {
  const releases = {
    repo: 'https://github.com/example/cleanphotos',
    latest: '1.2.3',
    versions: [{ version: '1.2.3', file: 'CleanPhotos-1.2.3.apk', size: 3, sha256: 'abc', date: '2026-10-30T00:00:00Z', github: 'https://example.invalid/r' }],
  };
  return {
    ASSETS: {
      async fetch(input) {
        const url = input instanceof URL ? input : new URL(input.url);
        if (url.pathname === '/api/releases.json') return new Response(JSON.stringify(releases), { status: 200 });
        if (url.pathname === '/apk/CleanPhotos-1.2.3.apk') return new Response('apk', { status: 200 });
        if (url.pathname === '/') return new Response('<html>zh</html>', { status: 200, headers: { 'content-type': 'text/html' } });
        return new Response('missing', { status: 404 });
      },
    },
  };
}

test('exact /download and /download/latest share the version header', async () => {
  const env = assetEnv();
  const exact = await worker.fetch(new Request('https://cleanphotos.example/download'), env);
  const latest = await worker.fetch(new Request('https://cleanphotos.example/download/latest'), env);
  assert.equal(exact.status, 200);
  assert.equal(latest.status, 200);
  assert.equal(exact.headers.get('content-type'), 'application/vnd.android.package-archive');
  assert.equal(exact.headers.get('x-app-version'), '1.2.3');
  assert.equal(latest.headers.get('x-app-version'), exact.headers.get('x-app-version'));
  assert.equal(await exact.text(), 'apk');
  assert.equal(await latest.text(), 'apk');
});

test('manual lang cookie wins over Accept-Language', async () => {
  const env = assetEnv();
  const forcedEn = await worker.fetch(new Request('https://cleanphotos.example/', { headers: { cookie: 'lang=en', 'accept-language': 'zh-CN' } }), env);
  assert.equal(forcedEn.status, 302);
  assert.equal(new URL(forcedEn.headers.get('location'), 'https://cleanphotos.example').pathname, '/en/');
  const forcedZh = await worker.fetch(new Request('https://cleanphotos.example/', { headers: { cookie: 'lang=zh', 'accept-language': 'fr-FR,en;q=0.8' } }), env);
  assert.equal(forcedZh.status, 200);
  assert.equal(await forcedZh.text(), '<html>zh</html>');
  const french = await worker.fetch(new Request('https://cleanphotos.example/', { headers: { 'accept-language': 'fr-FR,zh-CN;q=0.9' } }), env);
  assert.equal(french.status, 302);
});

test('wrangler sends exact /download to the worker', () => {
  const text = fs.readFileSync(path.join(siteRoot, 'wrangler.jsonc'), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:"'])\/\/.*$/gm, '$1');
  const json = JSON.parse(text);
  assert.ok(json.assets.run_worker_first.includes('/download'));
  assert.ok(json.assets.run_worker_first.includes('/download/*'));
});

function loadDemo() {
  const ids = new Map();
  const classList = () => {
    const set = new Set();
    return {
      add: (c) => set.add(c),
      remove: (c) => set.delete(c),
      toggle(c, force) {
        if (force === undefined) set.has(c) ? set.delete(c) : set.add(c);
        else if (force) set.add(c);
        else set.delete(c);
        return set.has(c);
      },
      contains: (c) => set.has(c),
    };
  };
  const make = (id) => {
    const listeners = {};
    let text = '';
    const node = {
      id, style: {}, dataset: {}, hidden: false, children: [],
      parentNode: { clientWidth: 1200 }, offsetWidth: 1, src: '',
      classList: classList(),
      // 浏览器会把 textContent 收成字符串。假 DOM 同样处理，计数断言才能对上。
      get textContent() { return text; },
      set textContent(value) { text = value == null ? '' : String(value); },
      setAttribute() {}, getAttribute() { return null; },
      addEventListener(type, fn) { (listeners[type] ||= []).push(fn); },
      appendChild(child) { node.children.push(child); return child; },
      querySelector() { return make(); },
      querySelectorAll() { return []; },
      focus() {}, setPointerCapture() {},
      getBoundingClientRect() { return { width: 348 }; },
      closest() { return null; },
    };
    node._listeners = listeners;
    return node;
  };
  const byId = (id) => {
    if (!ids.has(id)) {
      const node = make(id);
      if (id === 't') {
        node.textContent = JSON.stringify({
          weekday: 'Mon', kept: 'Kept', keptSub: 'No.%d', trashed: 'Gone', trashedSub: '%s MB',
          soundOn: 'on', soundOff: 'off', pause: 'pause', play: 'play',
        });
      }
      ids.set(id, node);
    }
    return ids.get(id);
  };
  const document = {
    cookie: '',
    querySelector(sel) {
      if (sel === '#promo' || sel === '.top') return null;
      if (sel.startsWith('#')) return byId(sel.slice(1));
      return make(sel);
    },
    querySelectorAll() { return []; },
    getElementById: byId,
    createElement: () => make('div'),
  };
  const context = {
    document, console,
    // 桌面演示：细指针为真才接管键盘。全部 false 会把「没有细指针」当成触屏，演示保持关闭。
    matchMedia: (query) => ({ matches: String(query).includes('pointer: fine'), addEventListener() {} }),
    addEventListener() {},
    scrollY: 0, innerHeight: 900,
    requestAnimationFrame: (fn) => setTimeout(() => fn(0), 0),
    performance: { now: () => Date.now() },
    navigator: {},
    setTimeout, clearTimeout,
    IntersectionObserver: class { observe() {} disconnect() {} unobserve() {} },
  };
  context.window = context;
  vm.runInNewContext(fs.readFileSync(path.join(siteRoot, 'static/js/site.js'), 'utf8'), context, { filename: 'site.js' });
  const screen = byId('screen');
  const pages = byId('pages');
  const press = (key) => {
    for (const fn of screen._listeners.keydown || []) fn({ key, preventDefault() {}, target: screen });
  };
  const visible = () => pages.children.filter((el) => el.style.visibility !== 'hidden');
  return { press, visible, ids };
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

test('undo of the photo now on screen does not hide it', async () => {
  const demo = loadDemo();
  demo.press('ArrowUp');
  await sleep(450);
  demo.press('ArrowLeft');
  await sleep(450);
  demo.press('z');
  await sleep(400);
  const shown = demo.visible();
  assert.equal(shown.length, 1);
  assert.equal(demo.ids.get('cF').textContent, '0');
  assert.match(demo.ids.get('file').textContent, /IMG_2041/);
});

test('ordinary undo restores the previous photo and the count', async () => {
  const demo = loadDemo();
  demo.press('ArrowUp');
  await sleep(450);
  assert.equal(demo.ids.get('cF').textContent, '1');
  demo.press('z');
  await sleep(400);
  assert.equal(demo.visible().length, 1);
  assert.equal(demo.ids.get('cF').textContent, '0');
  assert.match(demo.ids.get('file').textContent, /IMG_2041/);
});

test('rapid keys and a stale undo callback leave one visible photo', async () => {
  const burst = loadDemo();
  for (let i = 0; i < 6; i++) burst.press('ArrowUp');
  await sleep(500);
  assert.equal(burst.ids.get('cF').textContent, '1');
  assert.equal(burst.visible().length, 1);

  const demo = loadDemo();
  demo.press('ArrowUp');
  await sleep(450);
  demo.press('ArrowLeft');
  await sleep(450);
  demo.press('z');
  demo.press('ArrowUp');
  await sleep(500);
  assert.equal(demo.visible().length, 1);
  assert.equal(demo.ids.get('cF').textContent, '1');
  assert.match(fs.readFileSync(path.join(siteRoot, 'static/js/site.js'), 'utf8'), /remove\('on'\), 4000\)/);
});
