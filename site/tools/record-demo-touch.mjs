// 手机触屏录屏（无头，CDP 录帧 + 合成触摸）：滚到演示区 → 点「上手试试」→ 上滑 / 下滑 → 「完成」→ 继续往下滚。
// node tools/record-demo-touch.mjs [base] → shots-out/site-demo-v2.mp4
import { chromium } from '../../promo/node_modules/playwright-core/index.mjs';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
const B = process.argv[2] || 'http://127.0.0.1:8787';
const OUT = new URL('../shots-out/', import.meta.url).pathname, tmp = OUT + 'rec2/';
fs.rmSync(tmp, { recursive: true, force: true }); fs.mkdirSync(tmp, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--force-color-profile=srgb', '--headless=new'] });
const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'zh-CN' });
const page = await ctx.newPage();
await page.goto(B + '/', { waitUntil: 'networkidle' });
const cdp = await ctx.newCDPSession(page);
const frames = [];
cdp.on('Page.screencastFrame', f => { const file = `${tmp}${String(frames.length).padStart(5, '0')}.jpg`; fs.writeFileSync(file, Buffer.from(f.data, 'base64')); frames.push({ file, t: f.metadata.timestamp }); cdp.send('Page.screencastFrameAck', { sessionId: f.sessionId }).catch(() => {}); });
await cdp.send('Page.startScreencast', { format: 'jpeg', quality: 88, maxWidth: 780, maxHeight: 1688 });
const wait = ms => page.waitForTimeout(ms);
// 手指位置画一个小圆点，方便看清触摸
await page.addStyleTag({ content: '#finger{position:fixed;z-index:999;width:34px;height:34px;margin:-17px 0 0 -17px;border-radius:50%;background:rgba(255,255,255,.35);box-shadow:0 0 0 2px rgba(255,255,255,.7);pointer-events:none;display:none}' });
await page.evaluate(() => { const d = document.createElement('div'); d.id = 'finger'; document.body.appendChild(d); });
const finger = (x, y) => page.evaluate(([x, y]) => { const d = document.getElementById('finger'); if (x == null) d.style.display = 'none'; else Object.assign(d.style, { display: 'block', left: x + 'px', top: y + 'px' }); }, [x, y]);
async function swipe(x, y, dx, dy, ms = 420) {
  await finger(x, y); await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
  const n = Math.round(ms / 20);
  for (let i = 1; i <= n; i++) { const k = i / n, e = 1 - (1 - k) ** 2; await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x + dx * e, y: y + dy * e }] }); await finger(x + dx * e, y + dy * e); await wait(20); }
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] }); await finger(); await wait(450);
}
async function tap(sel) { const b = await page.locator(sel).boundingBox(); const x = b.x + b.width / 2, y = b.y + b.height / 2; await finger(x, y); await wait(250); await page.touchscreen.tap(x, y); await wait(200); await finger(); }
await wait(1500);
for (let i = 0; i < 5; i++) await swipe(200, 700, 0, -380);          // 往下滚，经过演示区也照常滚动
await wait(600);
const top = await page.evaluate(() => document.getElementById('screen').getBoundingClientRect().top);
await swipe(200, 600, 0, -(top - 150), 500); await wait(900);         // 停在演示区
let b = await page.locator('#screen').boundingBox();
await swipe(b.x + b.width / 2, b.y + b.height * .6, 0, -200);         // 未激活：在模拟手机上滑也只是滚动
await swipe(b.x + b.width / 2, b.y + b.height * .3, 0, 200); await wait(500);
await tap('#demoStart'); await wait(1600);
b = await page.locator('#screen').boundingBox();
const cx = b.x + b.width / 2, cy = b.y + b.height * .62;
await swipe(cx, cy, 0, -190, 520); await wait(900);                  // 上滑收藏
await swipe(cx, cy - 60, 0, 190, 520); await wait(900);              // 下滑移走
await swipe(cx + 70, cy, -150, 0, 360); await wait(700);              // 左滑下一张
await swipe(cx, cy, 0, -190, 520); await wait(1000);
await tap('#demoExit'); await wait(900);
for (let i = 0; i < 4; i++) await swipe(200, 700, 0, -380);          // 退出后继续往下滚
await wait(1200);
await cdp.send('Page.stopScreencast'); await wait(200);
await ctx.close(); await browser.close();
const list = frames.map((f, i) => `file '${f.file}'\nduration ${((frames[i + 1] ? frames[i + 1].t : f.t + 0.5) - f.t).toFixed(4)}`).join('\n') + `\nfile '${frames.at(-1).file}'\n`;
fs.writeFileSync(tmp + 'list.txt', list);
execFileSync('ffmpeg', ['-v', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', tmp + 'list.txt', '-vf', 'scale=780:-2:flags=lanczos,fps=30', '-c:v', 'libx264', '-crf', '22', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', OUT + 'site-demo-v2.mp4']);
fs.rmSync(tmp, { recursive: true, force: true });
console.log('ok', OUT + 'site-demo-v2.mp4');
