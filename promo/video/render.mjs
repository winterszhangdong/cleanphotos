// 逐帧渲染宣传片：本地起静态服务 → Chrome 打开 index.html → 每帧 seek(t) 截图 → ffmpeg 编码并混入配乐。
//   node video/render.mjs                    完整视频 → promo/out/cleanphotos-promo.mp4，并导出字幕
//   node video/render.mjs --still 12,35.5    只截几帧 PNG 到 promo/out/stills/，用来检查画面
//   node video/render.mjs --from 30 --to 40  只渲染一段（无配乐），用来检查动效
//   node video/render.mjs --clean            不烧录字幕的版本（上传 B 站 / YouTube 时配 srt 用）
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile, rm } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { extname, join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';
import { CUES, DURATION } from './cues.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');
const OUT = resolve(HERE, '../out');
// 海报和网页压缩版也写进 out/（不入库）
const SITE = OUT;
const FPS = 30;

const args = process.argv.slice(2);
const opt = k => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : undefined; };
const stills = opt('--still')?.split(',').map(Number);
const from = +(opt('--from') ?? 0), to = +(opt('--to') ?? DURATION);
const partial = opt('--from') !== undefined || opt('--to') !== undefined;
const clean = args.includes('--clean');

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.svg': 'image/svg+xml', '.jpg': 'image/jpeg', '.png': 'image/png', '.ttf': 'font/ttf', '.mp3': 'audio/mpeg' };
const server = createServer(async (req, res) => {
  const p = join(ROOT, decodeURIComponent(new URL(req.url, 'http://x').pathname));
  if (!p.startsWith(ROOT)) { res.writeHead(403).end(); return; }
  try { const body = await readFile(p); res.writeHead(200, { 'content-type': MIME[extname(p)] ?? 'application/octet-stream' }).end(body); }
  catch { res.writeHead(404).end(); }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const url = `http://127.0.0.1:${server.address().port}/promo/video/index.html`;

const browser = await chromium.launch({ channel: 'chrome', args: ['--force-color-profile=srgb', '--disable-lcd-text'] });
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
page.on('pageerror', e => { console.error('page error:', e); process.exit(1); });
await page.goto(url);
await page.evaluate(() => window.ready);
if (clean) await page.addStyleTag({ content: '#subs { display: none !important; }' });
await mkdir(OUT, { recursive: true });

if (stills) {
  await mkdir(join(OUT, 'stills'), { recursive: true });
  for (const t of stills) {
    await page.evaluate(t => seek(t), t);
    await page.screenshot({ path: join(OUT, 'stills', `t${t.toFixed(2)}.png`) });
  }
  console.log(`stills → ${join(OUT, 'stills')}`);
} else {
  const silent = join(OUT, partial ? `part-${from}-${to}.mp4` : clean ? 'video-only-clean.mp4' : 'video-only.mp4');
  const ff = spawn('ffmpeg', ['-y', '-v', 'error', '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'mjpeg', '-i', '-',
    '-c:v', 'libx264', '-preset', 'slow', '-crf', '17', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', silent], { stdio: ['pipe', 'inherit', 'inherit'] });
  const frames = Math.round((to - from) * FPS);
  const t0 = Date.now();
  for (let i = 0; i < frames; i++) {
    await page.evaluate(t => seek(t), from + i / FPS);
    const buf = await page.screenshot({ type: 'jpeg', quality: 95 });
    if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
    if (i % 60 === 0) process.stdout.write(`\rframe ${i}/${frames}  ${((Date.now() - t0) / 1000).toFixed(0)}s`);
  }
  ff.stdin.end();
  await new Promise((r, j) => ff.on('close', c => (c ? j(new Error('ffmpeg ' + c)) : r())));
  console.log(`\n${silent}`);

  if (!partial) {
    const final = join(OUT, clean ? 'cleanphotos-promo.clean.mp4' : 'cleanphotos-promo.mp4');
    await run('ffmpeg', ['-y', '-v', 'error', '-i', silent, '-i', join(ROOT, 'promo/music/ethernight-club.mp3'),
      '-filter_complex', `[1:a]atrim=0:${DURATION},asetpts=PTS-STARTPTS,afade=t=out:st=${DURATION - 3.2}:d=3.2,loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000[a]`,
      '-map', '0:v', '-map', '[a]', '-c:v', 'copy', '-c:a', 'aac', '-b:a', '192k', '-shortest', '-movflags', '+faststart', final]);
    if (!clean) {
      await run('ffmpeg', ['-y', '-v', 'error', '-ss', '16.1', '-i', final, '-frames:v', '1', '-q:v', '3', join(SITE, 'promo-poster.jpg')]);
      // 网页用的轻量版（约 18 MB）；out/ 里的是高码率母版
      await run('ffmpeg', ['-y', '-v', 'error', '-i', final, '-c:v', 'libx264', '-preset', 'slow', '-crf', '26', '-tune', 'grain', '-pix_fmt', 'yuv420p', '-c:a', 'copy', '-movflags', '+faststart', join(SITE, 'promo.mp4')]);
    }
    await rm(silent);
    console.log(final);
  }
}

// 字幕：zh / en / 中英双语 srt
const stamp = (s, sep) => { const ms = Math.round(s * 1000), h = Math.floor(ms / 3600000), m = Math.floor(ms / 60000) % 60, sec = Math.floor(ms / 1000) % 60; return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}${sep}${String(ms % 1000).padStart(3, '0')}`; };
const srt = pick => CUES.map(([a, b, zh, en], i) => `${i + 1}\n${stamp(a, ',')} --> ${stamp(b, ',')}\n${pick(zh, en)}\n`).join('\n');
await writeFile(join(OUT, 'cleanphotos-promo.zh.srt'), srt(zh => zh));
await writeFile(join(OUT, 'cleanphotos-promo.en.srt'), srt((zh, en) => en));
await writeFile(join(OUT, 'cleanphotos-promo.zh-en.srt'), srt((zh, en) => `${zh}\n${en}`));

await browser.close();
server.close();

function run(cmd, a) {
  return new Promise((r, j) => spawn(cmd, a, { stdio: 'inherit' }).on('close', c => (c ? j(new Error(`${cmd} ${c}`)) : r())));
}
