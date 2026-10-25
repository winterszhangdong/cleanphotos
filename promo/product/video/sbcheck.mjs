// 状态栏特写：每个实拍段截一张手机屏幕顶部（按 .screen 的屏幕外框裁），拼成一张图
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
const TS = (process.argv[2] ?? '3.8,4.5,6.8,7.5,9.6,10.5,11.7,12.6').split(',').map(Number);
const page = await browser.newPage({ viewport: { width: 1080, height: 1920 } });
await page.goto(`http://127.0.0.1:${server.address().port}/promo/product/video/index.html`); await page.evaluate(() => window.ready);
const files = [];
for (const t of TS) { await page.evaluate(t => seek(t), t);
  const r = await page.evaluate(() => { const b = document.querySelector('#rig .screen').getBoundingClientRect(); return { x: b.left, y: b.top, w: b.width, h: b.height }; });
  const x = Math.max(0, r.x - 16), y = Math.max(0, r.y - 16), w = Math.min(1080 - x, r.w + 32), h = Math.min(1920 - y, r.w * 300 / 560 + 16);
  const f = resolve(HERE, `../work/sb_${t.toFixed(2)}.png`); await page.screenshot({ path: f, clip: { x, y, width: w, height: h } }); files.push(f); console.log(t, r.x.toFixed(0), r.y.toFixed(0), r.w.toFixed(0)); }
await browser.close(); server.close();
const { spawnSync } = await import('node:child_process');
const n = files.length, fc = files.map((_, k) => `[${k}:v]scale=900:480:force_original_aspect_ratio=decrease,pad=920:500:(ow-iw)/2:(oh-ih)/2:color=0x202020[v${k}];`).join('') + files.map((_, k) => `[v${k}]`).join('') + `xstack=inputs=${n}:layout=` + files.map((_, k) => `${(k % 2) * 920}_${Math.floor(k / 2) * 500}`).join('|') + ':fill=0x202020[o]';
spawnSync('ffmpeg', ['-v', 'error', '-y', ...files.flatMap(f => ['-i', f]), '-filter_complex', fc, '-map', '[o]', resolve(HERE, '../work/statusbar-check.png')], { stdio: 'inherit' });
console.log('→ work/statusbar-check.png');
