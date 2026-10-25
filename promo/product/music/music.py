"""宣传片配乐：纯代码合成（numpy），无采样、无第三方素材，可自由使用。
120 BPM（一拍 0.5 s），时间轴与 video/timeline.js 对齐：
0 冲击 + 计数 → 2.0 关灯断奏 + 上升 → 3.0 落拍（亮灯）→ 3–20 律动段 → 20 收尾长音（总长 26.5 s）。"""
import numpy as np, sys
from scipy.signal import butter, sosfilt, fftconvolve
from scipy.io import wavfile

SR = 48000; DUR = float(sys.argv[2]) if len(sys.argv) > 2 else 26.5; OUT = sys.argv[1] if len(sys.argv) > 1 else 'music.wav'
N = int(SR * DUR); L = np.zeros(N); R = np.zeros(N)
rng = np.random.default_rng(3)
t_ = lambda d: np.arange(int(SR * d)) / SR
def lp(x, f, o=2): return sosfilt(butter(o, f, 'low', fs=SR, output='sos'), x)
def hp(x, f, o=2): return sosfilt(butter(o, f, 'high', fs=SR, output='sos'), x)
def bp(x, a, b, o=2): return sosfilt(butter(o, [a, b], 'band', fs=SR, output='sos'), x)
def add(x, at, g=1.0, pan=0.0, buf=None):
    i = int(at * SR); x = x[:max(0, N - i)]
    if i < 0 or len(x) == 0: return
    (buf or (L, R))[0][i:i + len(x)] += x * g * np.sqrt(.5 * (1 - pan)) * 1.414
    (buf or (L, R))[1][i:i + len(x)] += x * g * np.sqrt(.5 * (1 + pan)) * 1.414
def note(n): return 440 * 2 ** ((n - 69) / 12)

# ---------- 乐器 ----------
def kick(d=.45, hard=1.0):
    t = t_(d); f = 45 + 120 * np.exp(-t / .035) * hard; ph = 2 * np.pi * np.cumsum(f) / SR
    x = np.sin(ph) * np.exp(-t / (.16 + .1 * hard)); x[:200] += rng.normal(0, .5, 200) * np.linspace(1, 0, 200)
    return np.tanh(x * 1.6)
def clap(d=.35):
    t = t_(d); n = rng.normal(0, 1, len(t)); env = np.zeros(len(t))
    for k, o in enumerate((0, .011, .022)): env += (t >= o) * np.exp(-np.clip(t - o, 0, None) / (.008 if k < 2 else .09))
    return bp(n, 900, 5200) * env
def hat(d=.05, open_=False):
    t = t_(d); return hp(rng.normal(0, 1, len(t)), 7500) * np.exp(-t / (.09 if open_ else .012))
def boom(d=2.2):
    t = t_(d); f = 32 + 50 * np.exp(-t / .25); x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / .8)
    n = lp(rng.normal(0, 1, len(t)), 1800) * np.exp(-t / .12) * .7
    return np.tanh((x + n) * 1.4)
def crash(d=2.0):
    t = t_(d); return hp(rng.normal(0, 1, len(t)), 4000) * np.exp(-t / .55) * .5
def saw(f, t, det=0.0, ph=0.0): return 2 * ((t * f * (1 + det) + ph) % 1) - 1
def supersaw(notes, d, cutoff=2600):
    t = t_(d); x = np.zeros(len(t)); y = np.zeros(len(t))
    for n in notes:
        for k, det in enumerate(np.linspace(-.011, .011, 7)):
            s = saw(note(n), t, det, rng.random()); (x if k % 2 else y)[:] += s
    a = np.minimum(1, t / .01) * np.minimum(1, (d - t) / .05)
    return lp(x * a, cutoff) / (len(notes) * 4), lp(y * a, cutoff) / (len(notes) * 4)
def bass(n, d):
    t = t_(d); x = lp(saw(note(n), t), 380, 4) * .8 + np.sin(2 * np.pi * note(n) * t) * .7
    return x * np.minimum(1, t / .004) * np.exp(-t / .25) * np.minimum(1, (d - t) / .01)
def pluck(n, d=.3):
    t = t_(d); x = saw(note(n), t) * .6 + saw(note(n), t, .006) * .4
    return lp(x, 3200) * np.exp(-t / .09)
def blip(f, d=.05):
    t = t_(d); return np.sin(2 * np.pi * f * t) * np.exp(-t / .018)
def riser(d, f0=300, f1=9000):
    t = t_(d); n = rng.normal(0, 1, len(t)); out = np.zeros(len(t)); seg = 1200
    for i in range(0, len(t), seg):
        fr = f0 * (f1 / f0) ** (i / len(t)); out[i:i + seg] = bp(n[i:i + seg + 400], fr * .7, min(fr * 1.4, 20000))[:len(out[i:i + seg])]
    pitch = np.sin(2 * np.pi * np.cumsum(200 * (8 ** (t / d))) / SR) * .25
    return (out + pitch) * (t / d) ** 2
def whoosh(d=.6):
    t = t_(d); n = rng.normal(0, 1, len(t)); out = np.zeros(len(t)); seg = 1200
    for i in range(0, len(t), seg):
        fr = 400 + 5000 * np.sin(np.pi * i / len(t)); out[i:i + seg] = bp(n[i:i + seg + 400], fr * .6, fr * 1.5)[:len(out[i:i + seg])]
    return out * np.sin(np.pi * t / d) ** 2
def click():
    t = t_(.02); return hp(rng.normal(0, 1, len(t)), 2000) * np.exp(-t / .003)

# ---------- 编排 ----------
B = .5
drum = (np.zeros(N), np.zeros(N)); syn = (np.zeros(N), np.zeros(N)); fx = (np.zeros(N), np.zeros(N))
# 0–2：冲击 + 计数
add(boom(), 0, .9, buf=fx); add(crash(1.5), 0, .5, buf=fx); add(kick(hard=1.4), 0, 1.0, buf=drum)
for k in range(30):                       # 计数滴答，越来越快、越来越高
    at = 1.0 * (1 - (1 - k / 30) ** 1.6); add(blip(700 * 2 ** (k / 14)), at, .22 + .25 * k / 30, pan=(-.3 if k % 2 else .3), buf=fx)
for k in range(16): add(hat(), .0625 * k * 2, .25 + .02 * k, buf=drum)
add(kick(hard=1.2), 1.0, 1.0, buf=drum); add(clap(), 1.0, .7, buf=drum); add(boom(1.4), 1.0, .5, buf=fx)
for at in (1.5,): add(kick(), at, .8, buf=drum)
l, r = supersaw([57, 64, 72], 1.0, 1200); add(l, 1.0, .35, -.4, syn); add(r, 1.0, .35, .4, syn)
# 2–3：关灯，断奏 + 上升
add(click(), 2.0, .8, buf=fx); add(riser(1.0), 2.0, .55, buf=fx)
for k, at in enumerate(np.concatenate([np.arange(2.5, 2.75, .125), np.arange(2.75, 2.875, .0625), np.arange(2.875, 3.0, .03125)])):
    add(clap(.12), at, .25 + .4 * (at - 2.5), buf=drum)
add(click(), 2.5, .6, buf=fx); add(click(), 2.62, .5, buf=fx)
# 3：落拍
add(boom(), 3.0, 1.0, buf=fx); add(crash(), 3.0, .8, buf=fx)
# 3–20：律动段，两秒一小节（Am F C G 循环），切点都落在拍上；5 / 10 / 13 / 17 段落起点加镲
PROG = [([57, 60, 64, 69], 33), ([53, 57, 60, 65], 29), ([48, 55, 60, 64], 36), ([55, 59, 62, 67], 31)]
kicks = []
for k, bar in enumerate(range(3, 20, 2)):
    ch, root = PROG[k % 4]; d = min(2.0, 20 - bar)
    l, r = supersaw([n + 12 for n in ch], d, 2400 if bar < 7 else 3400); add(l, bar, .55, -.5, syn); add(r, bar, .55, .5, syn)
    for b in range(int(d / B)):
        at = bar + b * B
        if at >= 19.5: break                 # 最后两拍留给上升
        kicks.append(at); add(kick(hard=1.2 if b == 0 else 1.0), at, 1.0, buf=drum)
        if b % 2: add(clap(), at, .55, buf=drum)
        add(hat(.18, True), at + B / 2, .22, pan=.2, buf=drum)
        for s in range(4): add(hat(), at + s * B / 4, .12 + .06 * (s == 2), pan=-.2, buf=drum)
        add(bass(root, B / 2), at + B / 2, .55, buf=syn); add(bass(root + 12, B / 4), at + B * .75, .3, buf=syn)
    if bar >= 7:                          # 琶音
        arp = ch + [ch[1] + 12]
        for s in range(int(d / B) * 4): add(pluck(arp[s % len(arp)] + 12), bar + s * B / 4, .16, pan=(.5 if s % 2 else -.5), buf=syn)
for at in (4.75, 9.75, 12.75, 16.75): add(whoosh(.7), at - .25, .35, buf=fx)
for at in (5.0, 10.0, 13.0, 17.0): add(crash(1.2), at, .25, buf=fx)
# 19–20：上升，接片尾长音
add(riser(1.0, 600, 12000), 19.0, .4, buf=fx)
# 20：收尾（片尾，长音自然衰减到结尾）
E = 20.0
add(boom(3.0), E, 1.0, buf=fx); add(crash(3.0), E, .7, buf=fx); add(kick(hard=1.5), E, 1.1, buf=drum); kicks.append(E)
l, r = supersaw([57, 64, 69, 71, 76], 6.3, 1800); env = np.exp(-t_(6.3) / 2.2); add(l * env, E, .7, -.6, syn); add(r * env, E, .7, .6, syn)
add(np.sin(2 * np.pi * note(33) * t_(5.0)) * np.exp(-t_(5.0) / 1.6), E, .5, buf=syn)
arp = [69, 72, 76, 79, 81, 79, 76, 72]
for s in range(24): add(pluck(arp[s % 8] + 12, .4), E + .25 + s * .25, .14 * (1 - s / 24), pan=(.5 if s % 2 else -.5), buf=syn)

# ---------- 侧链、混响、母带 ----------
side = np.ones(N); tt = np.arange(N) / SR
for k in kicks:
    m = tt >= k; side[m] = np.minimum(side[m], 1 - .75 * np.exp(-(tt[m] - k) / .11))
def reverb(x, seed, dec=1.1, d=2.2):
    t = t_(d); ir = np.random.default_rng(seed).normal(0, 1, len(t)) * np.exp(-t / (dec / 6.9)); ir = lp(ir, 6000); ir /= np.sqrt((ir ** 2).sum())
    return fftconvolve(x, ir)[:len(x)]
for ch in (0, 1):
    s = syn[ch] * side; f = fx[ch]; d = drum[ch]
    wet = reverb(s * .6 + f * .5 + d * .15, 11 + ch)
    (L, R)[ch][:] += d + s + f + wet * .28
mix = np.stack([L, R], 1); mix = hp(mix.T, 25).T
mix /= np.abs(mix).max(); mix = np.tanh(mix * 1.8) / np.tanh(1.8)
fade = np.ones(N); fl = int(SR * 1.0); fade[-fl:] = np.linspace(1, 0, fl) ** 2; mix *= fade[:, None]
wavfile.write(OUT, SR, (mix * .89 * 32767).astype(np.int16)); print(OUT, DUR)
