// 量底部标签在每一帧的实际外框（含入场缩放 / 位移），检查左右安全边距
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '../../node_modules/playwright-core/index.mjs';
import { DURATION } from './timeline.js';
const HERE = dirname(fileURLToPath(import.meta.url)); const ROOT = resolve(HERE, '../../..');
const server = createServer(async (req, res) => { const p = join(ROOT, decodeURIComponent(new URL(req.url, 'http://x').pathname)); try { const body = await readFile(p); res.writeHead(200, { 'content-type': { '.html': 'text/html', '.js': 'text/javascript', '.jpg': 'image/jpeg', '.png': 'image/png', '.svg': 'image/svg+xml', '.ttf': 'font/ttf' }[extname(p)] ?? 'application/octet-stream' }).end(body); } catch { res.writeHead(404).end(); } });
await new Promise(r => server.listen(0, '127.0.0.1', r));
const browser = await chromium.launch({ channel: 'chrome', args: ['--headless=new'] });
for (const lang of ['zh', 'en']) {
  const page = await browser.newPage({ viewport: { width: 1080, height: 1920 } });
  await page.goto(`http://127.0.0.1:${server.address().port}/promo/v1.4/video/index.html?lang=${lang}`); await page.evaluate(() => window.ready);
  const res = await page.evaluate(async D => {
    const box = el => { const r = document.createRange(); r.selectNodeContents(el); return r.getBoundingClientRect(); };
    const out = {};
    for (let i = 0; i <= D * 60; i++) { const t = i / 60; await seek(t);
      if (+getComputedStyle(document.querySelector('#lab')).opacity < .01) continue;
      const k = document.querySelector('#lb').textContent; const a = box(document.querySelector('#lb')), b = box(document.querySelector('#ls'));
      const o = out[k] ??= { t0: t, L: 1e9, R: -1e9, B: -1e9, Lsub: 1e9, Rsub: -1e9 };
      o.L = Math.min(o.L, a.left); o.R = Math.max(o.R, a.right); o.B = Math.max(o.B, b.bottom); o.Lsub = Math.min(o.Lsub, b.left); o.Rsub = Math.max(o.Rsub, b.right); }
    return out; }, DURATION);
  console.log(`--- ${lang} (safe 6%: 65–1015)`);
  for (const [k, o] of Object.entries(res)) console.log(`${o.t0.toFixed(2)}s ${k.padEnd(22)} big ${o.L.toFixed(0)}–${o.R.toFixed(0)}  sub ${o.Lsub.toFixed(0)}–${o.Rsub.toFixed(0)}  bottom ${o.B.toFixed(0)}${o.L < 65 || o.R > 1015 || o.Lsub < 65 || o.Rsub > 1015 ? '  ← OUT' : ''}`);
}
await browser.close(); server.close();
