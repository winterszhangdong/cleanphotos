// 本地预览截图（无头浏览器，不抢焦点）：node tools/shoot.mjs [base]  → site/shots-out/
import { chromium } from '../../promo/node_modules/playwright-core/index.mjs';
const B = process.argv[2] || 'http://127.0.0.1:8787';
const OUT = new URL('../shots-out/', import.meta.url).pathname;
const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--force-color-profile=srgb', '--headless=new'] });
const jobs = [
  ['desktop', '/', { width: 1440, height: 900 }, 1, 'zh-CN'],
  ['desktop-en', '/en/', { width: 1440, height: 900 }, 1, 'en-US'],
  ['mobile', '/', { width: 390, height: 844 }, 2, 'zh-CN'],
  ['mobile-en', '/en/', { width: 390, height: 844 }, 2, 'en-US'],
  ['mobile-360', '/', { width: 360, height: 780 }, 2, 'zh-CN'],
  ['changelog-mobile', '/changelog/', { width: 390, height: 844 }, 2, 'zh-CN'],
  ['privacy-desktop-en', '/en/privacy/', { width: 1440, height: 900 }, 1, 'en-US'],
  ['404', '/nope', { width: 390, height: 844 }, 2, 'zh-CN'],
  ['mobile-reduced-motion', '/', { width: 390, height: 844 }, 2, 'zh-CN', 'reduce'],
];
for (const [name, path, viewport, dpr, locale, reducedMotion = 'no-preference'] of jobs) {
  let ctx = await browser.newContext({ viewport, deviceScaleFactor: dpr, locale, reducedMotion, isMobile: viewport.width < 500, hasTouch: viewport.width < 500 });
  let page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => m.type() === 'error' && errors.push(m.text()));
  await page.goto(B + path, { waitUntil: 'networkidle' });
  await page.screenshot({ path: `${OUT}${name}-fold.png` });
  // 整页图用 1 倍像素：Chrome 单张截图高度上限 16384 像素，2 倍会错位重复
  if (dpr > 1) { await ctx.close(); ctx = await browser.newContext({ viewport, deviceScaleFactor: 1, locale, reducedMotion, isMobile: viewport.width < 500, hasTouch: viewport.width < 500 }); page = await ctx.newPage(); await page.goto(B + path, { waitUntil: 'networkidle' }); }
  // 慢慢滚到底，让滚动显影都触发
  const h = await page.evaluate(() => document.documentElement.scrollHeight);
  for (let y = 0; y < h; y += viewport.height / 2) { await page.evaluate(y => scrollTo(0, y), y); await page.waitForTimeout(120); }
  await page.waitForTimeout(1800);
  await page.evaluate(() => scrollTo(0, 0)); await page.waitForTimeout(300);
  await page.screenshot({ path: `${OUT}${name}.png`, fullPage: true });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - innerWidth);
  console.log(name, 'height', h, 'x-overflow', overflow, errors.length ? 'ERR ' + errors.join(' | ') : '');
  await ctx.close();
}
await browser.close();
