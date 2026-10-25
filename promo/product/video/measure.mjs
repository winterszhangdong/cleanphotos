// 量所有字幕在每一帧的实际外框（含入场缩放 / 位移），检查左右 6% 和底部 15% 安全区
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
  await page.goto(`http://127.0.0.1:${server.address().port}/promo/product/video/index.html?lang=${lang}`); await page.evaluate(() => window.ready);
  const res = await page.evaluate(async D => {
    const SEL = ['#hz', '#he', '#lb', '#ls', '#cn', '#cl', '#dark', '#tz', '#title .en', '#tag', '#ut', '#us'];
    const vis = el => { let o = 1; for (let e = el; e && e !== document.body; e = e.parentElement) { const cs = getComputedStyle(e); if (cs.display === 'none') return 0; o *= +cs.opacity; } return o; };
    const box = el => { let L = 1e9, R = -1e9, T = 1e9, B = -1e9; const w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT); for (let n; (n = w.nextNode());) { if (!n.textContent.trim()) continue; const r = document.createRange(); r.selectNodeContents(n); for (const q of r.getClientRects()) { L = Math.min(L, q.left); R = Math.max(R, q.right); T = Math.min(T, q.top); B = Math.max(B, q.bottom); } } return { left: L, right: R, top: T, bottom: B, width: R > L ? R - L : 0 }; };
    const out = {};
    for (let i = 0; i <= D * 60; i++) { const t = i / 60; await seek(t);
      for (const sel of SEL) { const el = document.querySelector(sel); if (!el || !el.textContent.trim() || vis(el) < .05) continue;
        const a = box(el); if (!a.width) continue;
        const k = `${sel} ${(sel === '#ut' ? 'URL' : el.textContent.trim().slice(0, 14))}`.replace(/[\d,]+/g, 'N');
        const o = out[k] ??= { t0: t, t1: t, L: 1e9, R: -1e9, T: 1e9, B: -1e9 };
        o.t1 = t; o.L = Math.min(o.L, a.left); o.R = Math.max(o.R, a.right); o.T = Math.min(o.T, a.top); o.B = Math.max(o.B, a.bottom); } }
    return out; }, DURATION);
  console.log(`--- ${lang}（安全区：x 65–1015，底部 15% 以上 y ≤ 1632）`);
  for (const [k, o] of Object.entries(res)) console.log(`${o.t0.toFixed(2)}–${o.t1.toFixed(2)}s ${k.padEnd(30)} x ${o.L.toFixed(0)}–${o.R.toFixed(0)}  y ${o.T.toFixed(0)}–${o.B.toFixed(0)}${o.L < 65 || o.R > 1015 || o.B > 1632 ? '  ← OUT' : ''}`);
}
await browser.close(); server.close();
