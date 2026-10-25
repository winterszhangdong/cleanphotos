// 理照官网：语言记忆、顶栏、滚动显影、首屏视频，以及可以亲手滑的清理页演示。
(() => {
const $ = (s, r = document) => r.querySelector(s), $$ = (s, r = document) => [...r.querySelectorAll(s)];
const T = JSON.parse(($('#t') || {}).textContent || '{}');
const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;

// 手动切换语言后记住，首页按这个跳转
$$('[data-lang]').forEach(a => a.addEventListener('click', () => { document.cookie = `lang=${a.dataset.lang}; path=/; max-age=31536000; samesite=lax`; }));

// 顶栏：滚出首屏后加底色
const top = $('.top');
const onScroll = () => top && top.classList.toggle('solid', scrollY > 24);
addEventListener('scroll', onScroll, { passive: true }); onScroll();

// 滚动显影
if ('IntersectionObserver' in window && !reduce) {
  const io = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); } }), { rootMargin: '0px 0px -12% 0px' });
  $$('.reveal, .develop').forEach(el => io.observe(el));
} else $$('.reveal, .develop').forEach(el => el.classList.add('in'));

// 首屏视频：页面加载完再挂上视频源；减少动效时不自动播放；用户暂停后不再自动续播
const v = $('#promo');
if (v) {
  const playBtn = $('#vplay'), soundBtn = $('#vsound'), pauseBtn = $('#vpause');
  let userPaused = false;
  const attach = () => { if (v.dataset.src) { v.src = v.dataset.src; delete v.dataset.src; } };
  const icon = n => `<svg aria-hidden="true"><use href="#i-${n}"/></svg>`;
  const setSound = () => { soundBtn.innerHTML = icon(v.muted ? 'mute' : 'sound'); soundBtn.setAttribute('aria-label', v.muted ? T.soundOn : T.soundOff); };
  const setPause = () => { const on = !v.paused; pauseBtn.innerHTML = icon(on ? 'pause' : 'play'); pauseBtn.setAttribute('aria-label', on ? T.pause : T.play); };
  const play = () => { attach(); userPaused = false; v.play().catch(() => { playBtn.hidden = false; }); };
  const pause = () => { userPaused = true; v.pause(); };
  const toggle = () => (v.paused ? play() : pause());
  v.addEventListener('play', () => { playBtn.hidden = true; pauseBtn.hidden = false; soundBtn.hidden = false; setPause(); });
  v.addEventListener('pause', setPause);
  v.addEventListener('click', toggle);
  playBtn.onclick = play; pauseBtn.onclick = toggle;
  soundBtn.onclick = () => { v.muted = !v.muted; if (!v.muted) { v.currentTime = 0; play(); } setSound(); };
  if (reduce) { playBtn.hidden = false; soundBtn.hidden = true; pauseBtn.hidden = true; }
  else {
    const start = () => { if (!userPaused) play(); };
    const later = () => setTimeout(() => ('requestIdleCallback' in window ? requestIdleCallback(start, { timeout: 2000 }) : start()), 2500);
    document.readyState === 'complete' ? later() : addEventListener('load', later);
  }
  setSound(); setPause();
  // 不在视野里就暂停省电；回到视野只在用户没有手动暂停时续播
  new IntersectionObserver(([e]) => { if (!v.src || userPaused || !playBtn.hidden) return; e.isIntersecting ? v.play().catch(() => {}) : v.pause(); }).observe(v);
}

// ---------- 可交互的清理页（数值同 docs/interaction.md：阈值 120dp，封顶 170dp，缩到 0.9） ----------
const screen = $('#screen');
if (!screen) return;
const PHOTOS = [['hero', '07:42', 3.1, 1280], ['p129', '08:15', 2.8, 960], ['p186', '08:16', 2.4, 960], ['p219', '09:03', 4.6, 960], ['p102', '12:10', 3.4, 960],
  ['p164', '10:27', 3.9, 960], ['p113', '12:11', 2.2, 960], ['p225', '17:45', 3.2, 960], ['a', '14:38', 2.9, 720], ['g', '19:20', 3.8, 720]]
  .map(([f, tm, mb, h], i) => ({ f: `/photos/${f}.webp`, tm, mb, h, n: 'IMG_' + (2041 + i * 3) }));
const THRESHOLD = 120, CAP = 170, WIDTH = 348;
const pagesEl = $('#pages'), $id = id => document.getElementById(id);
const pages = PHOTOS.map((p, i) => {
  const el = document.createElement('div'); el.className = 'pg';
  const lazy = i > 1 ? ' loading="lazy"' : '';
  el.innerHTML = `<div class="amb"><img src="${p.f}" alt=""${lazy} width="720" height="${p.h}"></div><div class="ph"><img src="${p.f}" alt="" draggable="false"${lazy} width="720" height="${p.h}"></div>`;
  pagesEl.appendChild(el); return { el, ph: el.querySelector('.ph'), amb: el.querySelector('.amb'), img: el.querySelector('.ph img') };
});
const state = { idx: 0, marks: Array(PHOTOS.length).fill(''), history: [] };
const ACC = 'cubic-bezier(.3,0,.8,.15)', DEC = 'cubic-bezier(.05,.7,.1,1)', STD = 'cubic-bezier(.2,0,0,1)';
const glow = $('.glowUp', screen), vig = $('.vig', screen), cueUp = $id('cueUp'), cueDown = $id('cueDown'), chrome = $id('chrome');

function place(i, { x = 0, dy = 0, sc = 1, op = 1, filter = 'none', ring = false, amb = 1, tr = '' } = {}) {
  const p = pages[i]; if (!p) return;
  p.el.style.transition = tr ? `transform ${tr}, opacity ${tr}` : 'none';
  p.ph.style.transition = tr ? `transform ${tr}, box-shadow ${tr}` : 'none';
  p.img.style.transition = tr ? `filter ${tr}` : 'none';
  p.amb.style.transition = tr ? `opacity ${tr}` : 'none';
  p.el.style.transform = `translateX(${x}px)`; p.el.style.opacity = op; p.el.style.visibility = 'visible';
  p.ph.style.transform = `translateY(${dy}px) scale(${sc})`;
  p.ph.style.boxShadow = ring ? '0 0 0 1px rgba(242,169,59,.55), 0 0 48px rgba(242,169,59,.28), 0 32px 64px rgba(0,0,0,.6)' : 'none';
  p.img.style.filter = filter; p.amb.style.opacity = amb;
}
const hide = i => { if (pages[i]) pages[i].el.style.visibility = 'hidden'; };
function render() {
  const p = PHOTOS[Math.min(state.idx, PHOTOS.length - 1)];
  $id('time').textContent = `${T.weekday} · ${p.tm}`;
  $id('file').textContent = `${p.n} · ${p.mb.toFixed(1)} MB`;
  $id('segs').innerHTML = state.marks.map((m, i) => `<i class="${m || (i === state.idx ? 'c' : '')}"></i>`).join('');
  $id('cF').textContent = state.marks.filter(m => m === 'f').length;
  $id('cT').textContent = state.marks.filter(m => m === 't').length;
}
function showAt(i) { pages.forEach((_, j) => j !== i && hide(j)); place(i); render(); }

function setDrag(k, d) {
  const q = Math.min(Math.abs(d), CAP) / CAP, past = Math.abs(d) >= THRESHOLD;
  const up = k === 'up', down = k === 'down';
  place(state.idx, { dy: d, sc: 1 - .1 * q, amb: 1 - .7 * q, ring: up && past, filter: down ? `saturate(${1 - .65 * q}) brightness(${1 - .45 * q})` : 'none' });
  glow.style.opacity = up ? (past ? 1 : .38 * Math.abs(d) / THRESHOLD) : 0;
  vig.style.opacity = down ? (past ? 1 : .5 * Math.abs(d) / THRESHOLD) : 0;
  for (const [el, on] of [[cueUp, up], [cueDown, down]]) {
    el.style.transition = 'opacity .1s'; el.style.opacity = on ? Math.min(1, Math.abs(d) / 40) : 0;
    if (on && el.classList.contains('pre') === past) {
      el.classList.toggle('pre', !past);
      const ring = el.querySelector('.ring'); ring.style.transform = past ? 'scale(1.14)' : '';
      if (past) setTimeout(() => (ring.style.transform = ''), 120);
      if (navigator.vibrate) try { navigator.vibrate(past ? 12 : 6); } catch { /* 忽略 */ }
    }
  }
  chrome.style.opacity = d ? 0 : 1;
}
function clearCues(ms = 266) {
  for (const el of [cueUp, cueDown, glow, vig]) { el.style.transition = `opacity ${ms}ms linear`; el.style.opacity = 0; }
  setTimeout(() => { cueUp.classList.add('pre'); cueDown.classList.add('pre'); }, ms);
}

let undoTimer = 0, busy = false;
function showUndo(kind, i) {
  const u = $id('undo'); u.classList.remove('on'); void u.offsetWidth;
  u.classList.toggle('fav', kind === 'f');
  $id('undoImg').src = PHOTOS[i].f;
  $id('undoT').textContent = kind === 'f' ? T.kept : T.trashed;
  $id('undoS').textContent = kind === 'f' ? T.keptSub.replace('%d', i + 1) : T.trashedSub.replace('%s', PHOTOS[i].mb.toFixed(1));
  u.classList.add('on'); clearTimeout(undoTimer); undoTimer = setTimeout(() => u.classList.remove('on'), 4000);
}
function commit(k, d) {
  busy = true; stopHint();
  const i = state.idx, q = Math.min(Math.abs(d), CAP) / CAP;
  if (k === 'up') place(i, { dy: d - 900, sc: 1 - .1 * q, op: 0, ring: true, amb: 0, tr: `266ms ${ACC}` });
  else place(i, { dy: d + 700, sc: 1 - .1 * q, op: 0, amb: 0, filter: 'saturate(.35) brightness(.55)', tr: `266ms ${ACC}` });
  clearCues();
  state.marks[i] = k === 'up' ? 'f' : 't'; state.history.push({ i, k });
  showUndo(state.marks[i], i);
  next(i);
}
function next(i) {
  state.idx = i + 1; render();
  setTimeout(() => { chrome.style.transition = 'opacity .28s'; chrome.style.opacity = 1; }, 120);
  if (state.idx >= PHOTOS.length) { setTimeout(finish, 300); return; }
  const n = state.idx;
  place(n, { sc: .94, op: 0, filter: 'brightness(.6)' });
  requestAnimationFrame(() => requestAnimationFrame(() => place(n, { tr: `280ms ${DEC}` })));
  setTimeout(() => { hide(i); busy = false; }, 300);
}
function browse(dir) {
  const i = state.idx, j = i + dir;
  if (j < 0 || j >= PHOTOS.length) { place(i, { tr: `280ms ${STD}` }); return; }
  busy = true; stopHint();
  if (!state.marks[i]) state.marks[i] = 'k';
  place(i, { x: -dir * (WIDTH + 16), tr: `280ms ${STD}` });
  place(j, { x: dir * (WIDTH + 16) });
  requestAnimationFrame(() => requestAnimationFrame(() => place(j, { tr: `280ms ${STD}` })));
  state.idx = j; render();
  setTimeout(() => { hide(i); busy = false; }, 300);
}
function undo() {
  if (busy) return;
  const h = state.history.pop(); if (!h) return;
  $id('undo').classList.remove('on'); clearTimeout(undoTimer);
  const cur = state.idx;
  if (pages[cur]) place(cur, { sc: .94, op: 0, filter: 'brightness(.5)', tr: `300ms ${STD}` });
  state.marks[h.i] = ''; state.idx = h.i; $id('done').classList.remove('on');
  place(h.i, { dy: h.k === 'up' ? -760 : 760, sc: .9, filter: 'brightness(.4)' });
  requestAnimationFrame(() => requestAnimationFrame(() => place(h.i, { tr: `450ms ${DEC}` })));
  render(); setTimeout(() => hide(cur), 320);
}
function finish() {
  const m = state.marks;
  $id('dF').textContent = m.filter(x => x === 'f').length; $id('dT').textContent = m.filter(x => x === 't').length; $id('dK').textContent = m.filter(x => x === 'k').length;
  $id('done').classList.add('on'); busy = false; $id('again').focus({ preventScroll: true });
  if (coarse) setTimeout(() => { if (state.idx >= PHOTOS.length) setActive(false); }, 2500);
}
$id('again').onclick = () => { state.idx = 0; state.marks.fill(''); state.history = []; $id('done').classList.remove('on'); $id('undo').classList.remove('on'); showAt(0); screen.focus({ preventScroll: true }); };
$id('undoBtn').onclick = e => { e.stopPropagation(); undo(); };

// 第一次可以上手时，用一个光点示范一下上滑
const hand = $('.hand', screen);
let hinted = false;
function stopHint() { hinted = true; hand.classList.remove('go'); }
const showHint = () => { if (!reduce && !hinted) setTimeout(() => { if (!hinted) hand.classList.add('go'); }, 400); };

// 触屏上默认不接管手势：上下滑照常滚动页面；点「上手试试」才开始接管，点「完成」、滚出视野、
// 看完小结或 8 秒没操作都会退出。鼠标设备一直可用（滚轮不受影响）。
const coarse = matchMedia('(pointer: coarse)').matches || !matchMedia('(pointer: fine)').matches;
const startBtn = $id('demoStart'), exitBtn = $id('demoExit');
let active = false, idleT = 0;
function bumpIdle() { clearTimeout(idleT); if (coarse && active) idleT = setTimeout(() => setActive(false), 8000); }
function setActive(on) {
  active = on;
  screen.classList.toggle('live', on);
  startBtn.hidden = on || !coarse; exitBtn.hidden = !(on && coarse);
  if (!on && g) { g = null; place(state.idx, { tr: `280ms ${STD}` }); clearCues(100); chrome.style.opacity = 1; }
  bumpIdle();
}
startBtn.addEventListener('click', () => {
  if (state.idx >= PHOTOS.length) $id('again').onclick();
  setActive(true); showHint(); screen.focus({ preventScroll: true });
});
exitBtn.addEventListener('click', () => setActive(false));
if (coarse) new IntersectionObserver(([e]) => { if (active && e.intersectionRatio < .5) setActive(false); }, { threshold: [0, .5] }).observe(screen);
else new IntersectionObserver(([e], io) => { if (e.isIntersecting) { io.disconnect(); showHint(); } }, { threshold: .7 }).observe(screen);

// 手势：上下滑与左右滑在同一套指针事件里判定
let g = null;
screen.addEventListener('pointerdown', e => {
  if (!active) return;
  bumpIdle();
  if (busy || e.target.closest('.undo, .done') || state.idx >= PHOTOS.length) return;
  screen.setPointerCapture(e.pointerId); stopHint();
  g = { x0: e.clientX, y0: e.clientY, k: null, d: 0, t0: performance.now(), scale: screen.getBoundingClientRect().width / WIDTH };
});
screen.addEventListener('pointermove', e => {
  if (!g) return;
  bumpIdle();
  const dx = (e.clientX - g.x0) / g.scale, dy = (e.clientY - g.y0) / g.scale;
  if (!g.k && Math.hypot(dx, dy) > 8) { g.k = Math.abs(dy) > Math.abs(dx) ? 'v' : 'h'; if (g.k === 'v') $id('undo').classList.remove('on'); }
  if (g.k === 'v') { g.d = dy; setDrag(dy < 0 ? 'up' : 'down', dy); }
  if (g.k === 'h') { g.d = dx; place(state.idx, { x: dx }); const j = state.idx - Math.sign(dx); if (pages[j]) place(j, { x: dx + Math.sign(dx) * -(WIDTH + 16) }); }
});
const end = () => {
  if (!g) return;
  const { k, d, t0 } = g, v = Math.abs(d) / Math.max(1, performance.now() - t0) * 1000; g = null;
  if (k === 'v') {
    if (Math.abs(d) >= THRESHOLD || (v > 1200 && Math.abs(d) > 40)) commit(d < 0 ? 'up' : 'down', d);
    else { place(state.idx, { tr: `280ms ${STD}` }); clearCues(100); chrome.style.opacity = 1; }
  } else if (k === 'h') {
    const j = state.idx - Math.sign(d);
    if (Math.abs(d) >= 60 && pages[j]) browse(-Math.sign(d));
    else { place(state.idx, { tr: `280ms ${STD}` }); if (pages[j]) { place(j, { x: -Math.sign(d) * (WIDTH + 16), tr: `280ms ${STD}` }); setTimeout(() => hide(j), 300); } }
  }
};
screen.addEventListener('pointerup', end); screen.addEventListener('pointercancel', end);
screen.addEventListener('keydown', e => {
  if (!active) return;
  bumpIdle();
  if (e.key.toLowerCase() === 'z') { undo(); return; }
  if (busy || state.idx >= PHOTOS.length) return;
  if (e.key === 'ArrowUp') { e.preventDefault(); setDrag('up', -THRESHOLD); setTimeout(() => commit('up', -THRESHOLD), 120); }
  if (e.key === 'ArrowDown') { e.preventDefault(); setDrag('down', THRESHOLD); setTimeout(() => commit('down', THRESHOLD), 120); }
  if (e.key === 'ArrowRight') { e.preventDefault(); browse(1); }
  if (e.key === 'ArrowLeft') { e.preventDefault(); browse(-1); }
});

// 窄屏时整台手机按比例缩小
const box = $id('deviceBox'), device = $id('device');
let lastW = 0;
function fitDevice() {
  // 窄屏 / 触屏：最高只占视口高度的 62%，上下留出能直接滚动页面的空白
  const w = box.parentNode.clientWidth; if (w === lastW) return; lastW = w;
  const small = coarse || matchMedia('(max-width: 1000px)').matches;
  const s = Math.min(1, w / 360, small ? .62 * innerHeight / 760 : 1);
  device.style.transform = `scale(${s})`;
  Object.assign(box.style, { width: 360 * s + 'px', height: 760 * s + 'px' });
}
addEventListener('resize', fitDevice); fitDevice();
showAt(0);
setActive(!coarse);
})();
