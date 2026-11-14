"""生成演示相册：相机照片（含连拍组）、全景大图、视频、截图、聊天存图、那年今天。
全部用 Unsplash License 的照片（picsum.photos）或本地生成的界面截图，只推进 cleanphotos-promo 模拟器。"""
import json, os, random, subprocess, shutil, time
from datetime import datetime, timedelta
from PIL import Image, ImageFilter, ImageEnhance
import numpy as np, piexif

W = os.path.abspath('../work'); SRC = f'{W}/src'; OUT = f'{W}/media'
shutil.rmtree(OUT, ignore_errors=True)
random.seed(14)
TODAY = datetime(2026, 10, 24)
manifest = []

def exif_bytes(dt):
    s = dt.strftime('%Y:%m:%d %H:%M:%S').encode()
    return piexif.dump({'0th': {piexif.ImageIFD.Make: b'Google', piexif.ImageIFD.Model: b'Pixel 9', piexif.ImageIFD.DateTime: s},
                        'Exif': {piexif.ExifIFD.DateTimeOriginal: s, piexif.ExifIFD.DateTimeDigitized: s,
                                 piexif.ExifIFD.OffsetTimeOriginal: b'+08:00', piexif.ExifIFD.OffsetTime: b'+08:00'}})

def save(im, rel, name, dt, **kw):
    d = f'{OUT}/{rel}'; os.makedirs(d, exist_ok=True); p = f'{d}/{name}'
    if name.endswith('.png'): im.save(p, exif=exif_bytes(dt), optimize=False)
    else: im.save(p, 'JPEG', exif=exif_bytes(dt), **({'quality': 92} | kw))
    ts = dt.timestamp(); os.utime(p, (ts, ts)); manifest.append([rel + name, dt.isoformat()])
    return p

def colorful(p):
    a = np.asarray(Image.open(p).convert('RGB').resize((64, 64))).astype(float)
    rg = a[..., 0] - a[..., 1]; yb = .5 * (a[..., 0] + a[..., 1]) - a[..., 2]
    return np.sqrt(rg.std() ** 2 + yb.std() ** 2) + .3 * np.sqrt(rg.mean() ** 2 + yb.mean() ** 2) + .15 * a.mean()

ids = [l.strip() for l in open(f'{W}/ids.txt')]
score = {i: colorful(f'{SRC}/{i}.jpg') for i in ids}
ranked = sorted(ids, key=lambda i: -score[i])
src = lambda i: Image.open(f'{SRC}/{i}.jpg').convert('RGB')

def dhash(im):
    s = im.copy(); s.thumbnail((96, 96)); s = s.resize((9, 8), Image.BILINEAR)
    a = np.asarray(s).astype(int); l = (a[..., 0] * 77 + a[..., 1] * 150 + a[..., 2] * 29) >> 8
    return sum(1 << (y * 8 + x) for y in range(8) for x in range(8) if l[y, x] > l[y, x + 1])

def img_name(dt, ext='jpg', pre='IMG_'): return f"{pre}{dt.strftime('%Y%m%d_%H%M%S')}.{ext}"

used = set()
def take(n, pool=None):
    pool = pool or ranked; out = [i for i in pool if i not in used][:n]; used.update(out); return out

# ---- 今天：14 张最鲜艳的 + 1 组连拍 ----
def robust(i):
    s = src(i).resize((9, 8), Image.BILINEAR); a = np.asarray(s).astype(int); l = (a[..., 0] * 77 + a[..., 1] * 150 + a[..., 2] * 29) >> 8
    return np.abs(np.diff(l, axis=1)).mean()
burst_src = sorted(ranked[:60], key=lambda i: -robust(i))[:4]   # 四组连拍：鲜艳且结构反差大，哈希稳定
used.update(burst_src); print('burst src', burst_src)
today = take(14)
def burst(i, dt, n, blur_idx):
    base = src(i); w, h = base.size; frames = []
    for k in range(n):
        dx, dy = random.uniform(-.012, .012), random.uniform(-.012, .012)
        box = (int(w * (.04 + dx)), int(h * (.04 + dy)), int(w * (.96 + dx)), int(h * (.96 + dy)))
        f = base.crop(box).resize((1500, 2000), Image.LANCZOS)
        f = ImageEnhance.Brightness(f).enhance(random.uniform(.96, 1.04))
        if k in blur_idx: f = f.filter(ImageFilter.GaussianBlur(3.2 if k == blur_idx[0] else 1.6))
        frames.append(f)
    hs = [dhash(f) for f in frames]
    dist = [bin(hs[k] ^ hs[k - 1]).count('1') for k in range(1, n)]
    for k, f in enumerate(frames): save(f, 'DCIM/Camera/', img_name(dt + timedelta(seconds=k), pre='IMG_') .replace('.jpg', f'_BURST{k:02d}.jpg'), dt + timedelta(seconds=k))
    print('burst', i, 'hamming', dist)

def day(dt0, photos, slots):
    for i, t in zip(photos, slots):
        save(src(i), 'DCIM/Camera/', img_name(dt0 + t), dt0 + t)

def slots(n, h0=8, h1=21):
    mins = sorted(random.sample(range(h0 * 60, h1 * 60), n)); return [timedelta(minutes=m, seconds=random.randint(0, 59)) for m in mins]

day(TODAY, today, slots(14, 7, 11))
burst(burst_src[0], TODAY + timedelta(hours=9, minutes=41, seconds=5), 5, [3, 1])
# ---- 往前 45 天 ----
rest = [i for i in ranked if i not in used]
random.shuffle(rest)
d = 1; bi = 1
while rest and d <= 45:
    if random.random() < .82:
        n = min(len(rest), random.randint(3, 9)); ps, rest = rest[:n], rest[n:]
        dt0 = TODAY - timedelta(days=d); day(dt0, ps, slots(n))
        if d in (1, 3, 6) and bi < 4:
            burst(burst_src[bi], dt0 + timedelta(hours=7, minutes=12, seconds=30), [4, 4, 3][bi - 1], [2] if bi != 2 else [0]); bi += 1
    d += 1
print('rest unused', len(rest))

# ---- 全景大图：加颗粒，单张 > 10 MB ----
for k, i in enumerate(ranked[20:26]):
    im = src(i).resize((1500, 2000)); im = im.crop((0, 600, 1500, 1100)).resize((9600, 3200), Image.BICUBIC)
    a = np.asarray(im).astype(np.int16) + np.random.randint(-9, 10, (3200, 9600, 1), dtype=np.int16)
    dt = TODAY - timedelta(days=[0, 2, 5, 9, 13, 21][k], hours=-random.randint(12, 17))
    p = save(Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)), 'DCIM/Camera/', img_name(dt, pre='PANO_'), dt, quality=95)
    print('pano', os.path.getsize(p) // 1024 // 1024, 'MB')

# ---- 那年今天 ----
for y, n in ((2025, 5), (2024, 4), (2023, 3)):
    for i, t in zip(random.sample(ranked[30:120], n), slots(n, 9, 19)):
        dt = datetime(y, 10, 24) + t; save(src(i).rotate(0), 'DCIM/Camera/', img_name(dt), dt)

# ---- 聊天存图 ----
for k, i in enumerate(random.sample(ids, 90)):
    dt = TODAY - timedelta(days=random.randint(1, 40), minutes=random.randint(0, 600)) - timedelta(hours=1)
    im = src(i); im.thumbnail((1080, 1440))
    if k < 70: save(im, 'Pictures/WeiXin/', f'mmexport{int(dt.timestamp() * 1000)}.jpg', dt, quality=82)
    else: save(im, 'Pictures/QQ/', f'QQ图片{dt.strftime("%Y%m%d%H%M%S")}.jpg', dt, quality=82)

# ---- 截图 ----
shots = sorted(os.listdir(f'{W}/shots'))
for k, f in enumerate(shots):
    dt = TODAY - timedelta(days=random.randint(2, 44)) + timedelta(hours=random.randint(8, 23), minutes=random.randint(0, 59), seconds=random.randint(0, 59))
    if dt > TODAY + timedelta(hours=11): dt -= timedelta(hours=6)
    save(Image.open(f'{W}/shots/{f}').convert('RGB'), 'Pictures/Screenshots/', f"Screenshot_{dt.strftime('%Y%m%d-%H%M%S')}.png", dt)

# ---- 视频 ----
for k, (i, dur, dd) in enumerate(zip(ranked[14:22], [7, 12, 18, 9, 26, 15, 34, 11], [0, 0, 1, 2, 4, 7, 10, 16])):
    dt = TODAY - timedelta(days=dd) + timedelta(hours=random.randint(8, 10) if dd == 0 else random.randint(9, 20), minutes=random.randint(0, 59))
    d = f'{OUT}/DCIM/Camera'; p = f"{d}/VID_{dt.strftime('%Y%m%d_%H%M%S')}.mp4"
    utc = (dt - timedelta(hours=8)).strftime('%Y-%m-%dT%H:%M:%S.000000Z')
    fr = dur * 30
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-loop', '1', '-i', f'{SRC}/{i}.jpg', '-vf',
                    f"scale=3000:4000,zoompan=z='1+0.25*on/{fr}':x='iw/2-(iw/zoom/2)+on*0.6':y='ih/2-(ih/zoom/2)':d={fr}:s=1080x1440:fps=30,noise=alls=6:allf=t,format=yuv420p",
                    '-t', str(dur), '-c:v', 'libx264', '-preset', 'veryfast', '-b:v', '10M', '-metadata', f'creation_time={utc}', '-movflags', '+faststart', p], check=True)
    ts = dt.timestamp(); os.utime(p, (ts, ts)); manifest.append(['DCIM/Camera/' + os.path.basename(p), dt.isoformat()])
    print('video', os.path.basename(p), os.path.getsize(p) // 1024 // 1024, 'MB')

json.dump(manifest, open(f'{W}/manifest.json', 'w'), ensure_ascii=False, indent=0)
print('total files', len(manifest))
