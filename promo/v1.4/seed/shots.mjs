// 生成演示用「截图」：几种常见 App 界面模板 + 照片，1080×2400 PNG。只用于模拟器演示相册。
import { chromium } from '../../node_modules/playwright-core/index.mjs';
import { readdirSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
const SRC = resolve('../work/src'), OUT = resolve('../work/shots'); mkdirSync(OUT, { recursive: true });
const N = +(process.argv[2] ?? 160);
const pics = readdirSync(SRC).filter(f => f.endsWith('.jpg')).sort();
let seed = +(process.argv[3] ?? 7); const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
const pick = a => a[Math.floor(rnd() * a.length)];
const img = () => `file://${SRC}/${pick(pics)}`;
const words = '今天 天气 不错 我们 去 海边 吧 周末 一起 吃饭 这家 店 很好吃 记得 带 相机 照片 发 你 了 看看 哪张 好看 哈哈 收到 明天 见 地址 发我 一下 好的 没问题 价格 优惠 包邮 已售 评价 推荐 关注 点赞 分享 收藏 热门 新品 限时 秒杀'.split(' ');
const sent = n => Array.from({ length: n }, () => pick(words)).join('');
const bar = c => `<div style="height:110px;display:flex;align-items:center;justify-content:space-between;padding:0 50px;font:600 38px sans-serif;color:${c}"><span>${8 + Math.floor(rnd() * 14)}:${String(Math.floor(rnd() * 60)).padStart(2, '0')}</span><span>▂▄▆ 5G ▮</span></div>`;
const T = [
  () => { // 聊天
    const accent = pick(['#95EC69', '#4F9DFF', '#FFD24F']);
    let b = ''; for (let i = 0; i < 9; i++) { const me = rnd() > .5; const photo = rnd() > .78;
      b += `<div style="display:flex;justify-content:${me ? 'flex-end' : 'flex-start'};margin:26px 40px;gap:20px">${me ? '' : `<div style="width:96px;height:96px;border-radius:14px;background:url(${img()}) center/cover"></div>`}${photo ? `<div style="width:420px;height:520px;border-radius:18px;background:url(${img()}) center/cover"></div>` : `<div style="max-width:640px;padding:26px 32px;border-radius:18px;font:42px/1.45 sans-serif;background:${me ? accent : '#fff'};color:#111">${sent(3 + Math.floor(rnd() * 8))}</div>`}${me ? `<div style="width:96px;height:96px;border-radius:14px;background:url(${img()}) center/cover"></div>` : ''}</div>`; }
    return `<body style="margin:0;background:#EDEDED">${bar('#111')}<div style="height:120px;text-align:center;font:600 46px/120px sans-serif;color:#111;border-bottom:1px solid #ddd">${sent(2)}</div>${b}</body>`; },
  () => { // 购物
    const price = (rnd() * 900 + 9).toFixed(2);
    return `<body style="margin:0;background:#F4F4F4">${bar('#111')}<div style="height:1080px;background:url(${img()}) center/cover"></div><div style="background:#fff;padding:40px 50px"><div style="font:700 80px sans-serif;color:#FF4D2D">¥${price}</div><div style="font:500 46px/1.5 sans-serif;color:#222;margin-top:20px">${sent(10)}</div><div style="font:36px sans-serif;color:#999;margin-top:20px">已售 ${Math.floor(rnd() * 9000)}+ · ${sent(2)}</div></div><div style="display:flex;gap:20px;padding:30px 50px;background:#fff;margin-top:20px">${[0, 1, 2].map(() => `<div style="flex:1;height:300px;border-radius:16px;background:url(${img()}) center/cover"></div>`).join('')}</div><div style="position:fixed;bottom:0;left:0;right:0;height:170px;background:#fff;display:flex;gap:24px;padding:30px 50px;box-sizing:border-box"><div style="flex:1;border-radius:60px;background:#FFB000;font:600 44px/110px sans-serif;text-align:center;color:#fff">加入购物车</div><div style="flex:1;border-radius:60px;background:#FF4D2D;font:600 44px/110px sans-serif;text-align:center;color:#fff">立即购买</div></div></body>`; },
  () => { // 资讯
    return `<body style="margin:0;background:#fff">${bar('#111')}<div style="padding:30px 50px;font:700 64px/1.35 sans-serif;color:#111">${sent(9)}</div><div style="padding:0 50px;font:36px sans-serif;color:#999">${sent(2)} · ${Math.floor(rnd() * 23)} 小时前</div><div style="margin:40px 50px;height:620px;border-radius:20px;background:url(${img()}) center/cover"></div>${Array.from({ length: 5 }, () => `<p style="margin:0 50px 34px;font:44px/1.7 sans-serif;color:#333">${sent(18)}</p>`).join('')}</body>`; },
  () => { // 社交信息流（深色）
    return `<body style="margin:0;background:#111;color:#eee">${bar('#eee')}${[0, 1].map(() => `<div style="padding:30px 40px"><div style="display:flex;gap:24px;align-items:center"><div style="width:100px;height:100px;border-radius:50%;background:url(${img()}) center/cover"></div><div style="font:600 42px sans-serif">${sent(2)}</div></div><div style="margin-top:24px;font:42px/1.5 sans-serif">${sent(8)}</div><div style="margin-top:24px;display:grid;grid-template-columns:1fr 1fr;gap:10px">${[0, 1, 2, 3].map(() => `<div style="height:420px;border-radius:12px;background:url(${img()}) center/cover"></div>`).join('')}</div><div style="margin-top:20px;font:36px sans-serif;color:#888">♡ ${Math.floor(rnd() * 999)}  · 💬 ${Math.floor(rnd() * 99)}</div></div>`).join('')}</body>`; },
];
const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1080, height: 2400 } });
for (let i = 0; i < N; i++) {
  writeFileSync(`${OUT}/_t.html`, `<meta charset=utf-8><style>*{box-sizing:border-box}body{overflow:hidden;height:2400px}</style>` + pick(T)());
  await page.goto(`file://${OUT}/_t.html`, { waitUntil: 'load' });
  await page.screenshot({ path: `${OUT}/s${String(i + +(process.argv[4] ?? 0)).padStart(3, "0")}.png` });
}
await browser.close();
console.log('done', N);
