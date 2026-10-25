// 触屏演示回归测试（无头，390×844 触屏）：未激活时上下滑能滚动页面、左右滑不翻页；激活后滑动会收藏 / 移走且不滚动；退出后恢复滚动。
// 同时检查首屏视频暂停后不会自动续播，以及桌面端滚轮能穿过演示区。 node tools/test-demo-touch.mjs [base]
import { chromium } from '../../promo/node_modules/playwright-core/index.mjs';
const B = process.argv[2] || 'http://127.0.0.1:8787';
const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--headless=new'] });
let fails = 0;
const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) fails++; };
{
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'zh-CN' });
  const page = await ctx.newPage(); await page.goto(B + '/', { waitUntil: 'networkidle' });
  const cdp = await ctx.newCDPSession(page);
  const swipe = async (x, y, dx, dy, steps = 14) => {
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
    for (let i = 1; i <= steps; i++) { await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x + dx * i / steps, y: y + dy * i / steps }] }); await page.waitForTimeout(16); }
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] }); await page.waitForTimeout(900);
  };
  const st = () => page.evaluate(() => ({ y: Math.round(scrollY), f: +document.getElementById('cF').textContent, t: +document.getElementById('cT').textContent, live: document.getElementById('screen').classList.contains('live'), start: !document.getElementById('demoStart').hidden, exit: !document.getElementById('demoExit').hidden, file: document.getElementById('file').textContent }));
  // 视频：等自动播放，暂停，滚走再滚回，确认没有续播
  await page.waitForTimeout(3500);
  const vp = () => page.evaluate(() => document.getElementById('promo').paused);
  ok(!(await vp()), 'hero video autoplays');
  await page.locator('#vpause').tap(); await page.waitForTimeout(300);
  ok(await vp(), 'pause button pauses video');
  ok((await page.getAttribute('#vpause', 'aria-label')) === '播放视频', 'pause button label flips to 播放视频');
  await page.evaluate(() => scrollTo(0, 2500)); await page.waitForTimeout(500); await page.evaluate(() => scrollTo(0, 0)); await page.waitForTimeout(800);
  ok(await vp(), 'video stays paused after scrolling away and back');
  await page.locator('#promo').tap(); await page.waitForTimeout(400);
  ok(!(await vp()), 'tapping the video resumes');
  // 演示区
  const top = await page.evaluate(() => document.getElementById('screen').getBoundingClientRect().top + scrollY - 140);
  await page.evaluate(y => scrollTo(0, y), top); await page.waitForTimeout(800);
  const box = await page.locator('#screen').boundingBox();
  ok(box.height <= 844 * .63, `mock phone height ${Math.round(box.height)}px ≤ 62% of 844`);
  const cx = box.x + box.width / 2, cy = box.y + box.height * .6;
  let s0 = await st();
  ok(!s0.live && s0.start && !s0.exit, 'demo inactive by default, 「点一下，上手试试」 shown');
  await swipe(cx, cy, 0, -260);
  let s1 = await st();
  ok(s1.y > s0.y + 100 && s1.f === 0 && s1.t === 0, `inactive vertical swipe scrolls page (${s0.y} → ${s1.y}), no keep/trash`);
  await page.evaluate(y => scrollTo(0, y), top); await page.waitForTimeout(600);
  s0 = await st();
  await swipe(cx + 60, cy, -150, 0);
  s1 = await st();
  ok(s1.file === s0.file && s1.f === 0, 'inactive horizontal swipe does not flip photos');
  await page.locator('#demoStart').tap(); await page.waitForTimeout(500);
  s0 = await st();
  ok(s0.live && !s0.start && s0.exit, 'tap activates; 「完成」 shown');
  const b2 = await page.locator('#screen').boundingBox(); const cy2 = b2.y + b2.height * .6;
  await swipe(cx, cy2, 0, -200);
  s1 = await st();
  ok(s1.f === 1 && Math.abs(s1.y - s0.y) < 4, `active swipe up keeps (kept ${s1.f}), page did not scroll (${s0.y} → ${s1.y})`);
  await swipe(cx, cy2 - 60, 0, 200);
  s1 = await st();
  ok(s1.t === 1 && Math.abs(s1.y - s0.y) < 4, `active swipe down trashes (trashed ${s1.t}), no scroll`);
  await page.locator('#demoExit').tap(); await page.waitForTimeout(400);
  s0 = await st();
  ok(!s0.live && s0.start, 'Done exits demo');
  await swipe(cx, cy2, 0, -260);
  s1 = await st();
  ok(s1.y > s0.y + 100, `after exit, swipe scrolls again (${s0.y} → ${s1.y})`);
  // 8 秒无操作自动退出
  await page.evaluate(y => scrollTo(0, y), top); await page.waitForTimeout(500);
  await page.locator('#demoStart').tap(); await page.waitForTimeout(8600);
  ok(!(await st()).live, 'auto-deactivates after 8 s idle');
  // 滚出视野自动退出
  await page.locator('#demoStart').tap(); await page.waitForTimeout(300);
  await page.evaluate(y => scrollTo(0, y + 900), top); await page.waitForTimeout(600);
  ok(!(await st()).live, 'auto-deactivates when scrolled out of view');
  await ctx.close();
}
{
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  const page = await ctx.newPage(); await page.goto(B + '/', { waitUntil: 'networkidle' });
  const top = await page.evaluate(() => document.getElementById('screen').getBoundingClientRect().top + scrollY - 60);
  await page.evaluate(y => scrollTo(0, y), top); await page.waitForTimeout(500);
  const live = await page.evaluate(() => document.getElementById('screen').classList.contains('live') && document.getElementById('demoStart').hidden);
  ok(live, 'desktop: demo active, no overlay');
  const box = await page.locator('#screen').boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  const y0 = await page.evaluate(() => scrollY);
  await page.mouse.wheel(0, 500); await page.waitForTimeout(600);
  const y1 = await page.evaluate(() => scrollY);
  ok(y1 > y0 + 200, `desktop: wheel over demo scrolls page (${y0} → ${y1})`);
  await ctx.close();
}
await browser.close();
console.log(fails ? `${fails} FAILED` : 'ALL PASS');
process.exit(fails ? 1 : 0);
