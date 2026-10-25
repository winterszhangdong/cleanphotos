// 逐帧渲染 产品宣传片（竖版 1080×1920 @60fps）：本地静态服务 → Chrome 打开 index.html → 每帧 await seek(t) 截图 → ffmpeg。
//   node render.mjs                     成片（中文）→ ../out/cleanphotos-promo.mp4 + 海报 + 联系表
//   node render.mjs --lang en           英文版 → ../out/cleanphotos-promo.en.mp4
//   node render.mjs --still 1,3.2,8.5   只截几帧 PNG 到 ../work/stills/
//   node render.mjs --from 3 --to 7     只渲染一段（无配乐）
import { createServer } from 'node:http';
import { readFile, mkdir, rm } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { extname, join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '../../node_modules/playwright-core/index.mjs';
import { DURATION, FPS } from './timeline.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../../..');
const OUT = resolve(HERE, '../out'), WORK = resolve(HERE, '../work');
const args = process.argv.slice(2);
const opt = k => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : undefined; };
const stills = opt('--still')?.split(',').map(Number);
const lang = opt('--lang') ?? 'zh';
const from = +(opt('--from') ?? 0), to = +(opt('--to') ?? DURATION);
const partial = opt('--from') !== undefined || opt('--to') !== undefined;
const suffix = lang === 'en' ? '.en' : '';

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.svg': 'image/svg+xml', '.jpg': 'image/jpeg', '.png': 'image/png', '.ttf': 'font/ttf' };
const server = createServer(async (req, res) => {
  const p = join(ROOT, decodeURIComponent(new URL(req.url, 'http://x').pathname));
  if (!p.startsWith(ROOT)) { res.writeHead(403).end(); return; }
  try { const body = await readFile(p); res.writeHead(200, { 'content-type': MIME[extname(p)] ?? 'application/octet-stream' }).end(body); }
  catch { res.writeHead(404).end(); }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const url = `http://127.0.0.1:${server.address().port}/promo/product/video/index.html?lang=${lang}`;
const browser = await chromium.launch({ channel: 'chrome', args: ['--force-color-profile=srgb', '--disable-lcd-text', '--headless=new'] });
const page = await browser.newPage({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: 1 });
page.on('pageerror', e => { console.error('page error:', e); process.exit(1); });
page.on('console', m => { if (m.type() === 'error') console.error('console:', m.text()); }); page.on('response', r => { if (r.status() >= 400) console.error('HTTP', r.status(), r.url()); });
await page.goto(url);
await page.evaluate(() => window.ready);
await mkdir(OUT, { recursive: true });

if (stills) {
  await mkdir(join(WORK, 'stills'), { recursive: true });
  for (const t of stills) { await page.evaluate(t => seek(t), t); await page.screenshot({ path: join(WORK, 'stills', `t${t.toFixed(2)}${suffix}.png`) }); }
  console.log('stills →', join(WORK, 'stills'));
} else {
  const silent = join(WORK, partial ? `part-${from}-${to}.mp4` : `video-only${suffix}.mp4`);
  const ff = spawn('ffmpeg', ['-y', '-v', 'error', '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'mjpeg', '-i', '-',
    '-c:v', 'libx264', '-preset', 'slow', '-crf', '16', '-pix_fmt', 'yuv420p', '-r', String(FPS), '-movflags', '+faststart', silent], { stdio: ['pipe', 'inherit', 'inherit'] });
  const frames = Math.round((to - from) * FPS); const t0 = Date.now();
  for (let i = 0; i < frames; i++) {
    await page.evaluate(t => seek(t), from + i / FPS);
    const buf = await page.screenshot({ type: 'jpeg', quality: 94 });
    if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
    if (i % 60 === 0) process.stdout.write(`\rframe ${i}/${frames}  ${((Date.now() - t0) / 1000).toFixed(0)}s`);
  }
  ff.stdin.end();
  await new Promise((r, j) => ff.on('close', c => (c ? j(new Error('ffmpeg ' + c)) : r())));
  console.log('\n' + silent);
  if (!partial) {
    const wav = join(WORK, 'music.wav');
    await run(join(WORK, 'venv/bin/python'), [resolve(HERE, '../music/music.py'), wav, String(DURATION)]);
    const final = join(OUT, `cleanphotos-promo${suffix}.mp4`);
    await run('ffmpeg', ['-y', '-v', 'error', '-i', silent, '-i', wav, '-filter_complex', '[1:a]loudnorm=I=-14:TP=-1.0:LRA=9,aresample=48000[a]',
      '-map', '0:v', '-map', '[a]', '-c:v', 'copy', '-c:a', 'aac', '-b:a', '256k', '-shortest', '-movflags', '+faststart', final]);
    console.log(final);
  }
}
await browser.close(); server.close();
function run(cmd, a) { return new Promise((r, j) => spawn(cmd, a, { stdio: 'inherit' }).on('close', c => (c ? j(new Error(`${cmd} ${c}`)) : r()))); }
