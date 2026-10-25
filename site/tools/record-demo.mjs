// 录一段手机尺寸的滚动 + 滑动演示（无头）：node tools/record-demo.mjs [base] → shots-out/site-demo.mp4
import { chromium } from '../../promo/node_modules/playwright-core/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
const B = process.argv[2] || 'http://127.0.0.1:8787';
const OUT = new URL('../shots-out/', import.meta.url).pathname;
const tmp = OUT + 'rec/'; fs.rmSync(tmp, { recursive: true, force: true });
const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--force-color-profile=srgb', '--headless=new'] });
const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, locale: 'zh-CN' });
fs.mkdirSync(tmp, { recursive: true });
const page = await ctx.newPage();
await page.goto(B + '/', { waitUntil: 'networkidle' });
// 用 CDP 录屏帧（Playwright 自带录像要额外下载 ffmpeg）
const cdp = await ctx.newCDPSession(page);
const frames = [];
cdp.on('Page.screencastFrame', async f => { const file = `${tmp}${String(frames.length).padStart(5, '0')}.jpg`; fs.writeFileSync(file, Buffer.from(f.data, 'base64')); frames.push({ file, t: f.metadata.timestamp }); cdp.send('Page.screencastFrameAck', { sessionId: f.sessionId }).catch(() => {}); });
await cdp.send('Page.startScreencast', { format: 'jpeg', quality: 88, maxWidth: 780, maxHeight: 1688, everyNthFrame: 1 });
const wait = ms => page.waitForTimeout(ms);
const smooth = async (to, ms) => page.evaluate(([to, ms]) => new Promise(r => { const y0 = scrollY, t0 = performance.now(); const f = t => { const k = Math.min(1, (t - t0) / ms), e = k < .5 ? 2 * k * k : 1 - (-2 * k + 2) ** 2 / 2; scrollTo(0, y0 + (to - y0) * e); k < 1 ? requestAnimationFrame(f) : r(); }; requestAnimationFrame(f); }), [to, ms]);
await wait(2600);
const tryTop = await page.evaluate(() => document.querySelector('#screen').getBoundingClientRect().top + scrollY - 76);
await smooth(tryTop, 1800); await wait(2600);   // 光点示范
const box = await page.locator('#screen').boundingBox();
const cx = box.x + box.width / 2, cy = box.y + box.height * .55;
async function drag(dx, dy, ms = 520, hold = 260) {
  await page.mouse.move(cx, cy); await page.mouse.down();
  const n = Math.round(ms / 16);
  for (let i = 1; i <= n; i++) { const k = i / n, e = 1 - (1 - k) ** 2; await page.mouse.move(cx + dx * e, cy + dy * e); await wait(16); }
  await wait(hold); await page.mouse.up(); await wait(700);
}
await drag(0, -190);           // 上滑收藏
await wait(500);
await drag(0, 190);            // 下滑移走
await wait(500);
await drag(0, -80, 420, 400);  // 没过阈值，回弹
await drag(-170, 0, 380, 80);  // 左滑下一张
await drag(0, 200);            // 下滑
await wait(400);
await page.locator('#undoBtn').click(); await wait(1200);  // 撤回
await drag(0, -200); await wait(1200);
await smooth(tryTop + 1400, 2200); await wait(2000);
await cdp.send('Page.stopScreencast'); await wait(200);
await ctx.close(); await browser.close();
// 按真实时间戳拼成 30fps 视频
const list = frames.map((f, i) => `file '${f.file}'\nduration ${((frames[i + 1] ? frames[i + 1].t : f.t + 0.5) - f.t).toFixed(4)}`).join('\n') + `\nfile '${frames.at(-1).file}'\n`;
fs.writeFileSync(tmp + 'list.txt', list);
execFileSync('ffmpeg', ['-v', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', tmp + 'list.txt', '-vf', 'scale=780:-2:flags=lanczos,fps=30', '-c:v', 'libx264', '-crf', '22', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', OUT + 'site-demo.mp4']);
fs.rmSync(tmp, { recursive: true, force: true });
console.log('ok', OUT + 'site-demo.mp4');
