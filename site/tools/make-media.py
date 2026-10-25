"""一次性生成官网的媒体文件 → site/static/（生成物入库，CI 不需要再跑）。
需要：site/.venv（pillow、fonttools、brotli）、ffmpeg。
  site/.venv/bin/python site/tools/make-media.py
来源（只读）：promo/product/out/cleanphotos-promo.mp4、promo/v1.4/work/frames（1.4 release 包录屏）、
promo/assets/photos（示例照片）、site/static/img/icon.svg（图标，已在仓库里）、icon.png、app/src/main/res/font。"""
import os, subprocess, shutil
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from fontTools.ttLib import TTFont

SITE = os.path.dirname(os.path.dirname(os.path.abspath(__file__))); REPO = os.path.dirname(SITE)
S = f'{SITE}/static'; FR = f'{REPO}/promo/v1.4/work/frames'
for d in ('media', 'shots', 'photos', 'fonts', 'img'): os.makedirs(f'{S}/{d}', exist_ok=True)
run = lambda *a: subprocess.run(a, check=True)

# ---- 字体：只带拉丁字体（数字、等宽）；中文用系统字体（安卓自带思源黑体 / 苹方），首屏不等字体
for name in ('schibsted_grotesk_600', 'schibsted_grotesk_700', 'ibm_plex_mono_400', 'ibm_plex_mono_500'):
    f = TTFont(f'{REPO}/app/src/main/res/font/{name}.ttf'); f.flavor = 'woff2'; f.save(f'{S}/fonts/{name}.woff2')

# ---- 宣传片：竖版 576×1024 H.264（带声音，默认静音播放）；海报
V = f'{REPO}/promo/product/out/cleanphotos-promo.mp4'
run('ffmpeg', '-v', 'error', '-y', '-i', V, '-vf', 'scale=576:1024:flags=lanczos', '-c:v', 'libx264', '-profile:v', 'high', '-preset', 'slow', '-crf', '28', '-maxrate', '1.6M', '-bufsize', '4M',
    '-pix_fmt', 'yuv420p', '-r', '30', '-c:a', 'aac', '-b:a', '96k', '-movflags', '+faststart', f'{S}/media/promo-576.mp4')
run('ffmpeg', '-v', 'error', '-y', '-i', V.replace('.mp4', '.en.mp4'), '-vf', 'scale=576:1024:flags=lanczos', '-c:v', 'libx264', '-profile:v', 'high', '-preset', 'slow', '-crf', '28', '-maxrate', '1.6M', '-bufsize', '4M',
    '-pix_fmt', 'yuv420p', '-r', '30', '-c:a', 'aac', '-b:a', '96k', '-movflags', '+faststart', f'{S}/media/promo-576.en.mp4')
run('ffmpeg', '-v', 'error', '-y', '-ss', '1.6', '-i', V, '-frames:v', '1', '-vf', 'scale=720:1280', f'{SITE}/.poster.png')
Image.open(f'{SITE}/.poster.png').convert('RGB').save(f'{S}/media/poster.webp', quality=78); Image.open(f'{SITE}/.poster.png').convert('RGB').save(f'{S}/media/poster.jpg', quality=82); os.remove(f'{SITE}/.poster.png')

# ---- 真实 App 截图（1.4 release 包，演示相册）。录屏的状态栏贴边还带 3G：用顶行拉伸盖掉，网页的手机框自己画状态栏
SHOTS = {'grid': 'rv/00030', 'compare': 'cmp/00200', 'zoom': 'cmp/00640', 'groups': 'scope/00110', 'big': 'scope/00320', 'shots': 'scope/00480',
         'video': 'rv/00150', 'favorite': 'rv/00370', 'trash': 'tr1/00300', 'freed': 'tr2/00300'}
for k, f in SHOTS.items():
    im = Image.open(f'{FR}/{f}.jpg').convert('RGB'); w, h = im.size
    top = im.crop((0, 0, w, 12)).resize((w, 72)); im.paste(top, (0, 0))
    im.resize((540, 1200), Image.LANCZOS).save(f'{S}/shots/{k}.webp', quality=80, method=6)

# ---- 演示用照片（Unsplash，见 promo/assets/photos/CREDITS.md）
DEMO = ['hero', 'p129', 'p186', 'p219', 'p102', 'p164', 'p113', 'p225', 'a', 'g']
for n in DEMO:
    im = Image.open(f'{REPO}/promo/assets/photos/{n}.jpg').convert('RGB'); im.thumbnail((720, 1280), Image.LANCZOS); im.save(f'{S}/photos/{n}.webp', quality=78, method=6)
shutil.copy(f'{REPO}/promo/assets/photos/CREDITS.md', f'{S}/photos/CREDITS.md')

# ---- 图标（只复制、缩放，不改图）
# icon.svg 已在 site/static/img/（由 app/src/main/res/drawable/ic_launcher_*.xml 转换），不再复制
ico = Image.open(f'{REPO}/icon.png').convert('RGBA')
for sz, name in ((32, 'favicon-32.png'), (180, 'apple-touch-icon.png'), (192, 'icon-192.png'), (512, 'icon-512.png')): ico.resize((sz, sz), Image.LANCZOS).save(f'{S}/img/{name}')

# ---- 分享卡片 1200×630
def og(lang):
    W, H = 1200, 630; im = Image.new('RGB', (W, H), (15, 14, 13)); d = ImageDraw.Draw(im)
    glow = Image.new('L', (W, H), 0); ImageDraw.Draw(glow).ellipse((-200, 260, 900, 1100), fill=120); glow = glow.filter(ImageFilter.GaussianBlur(140))
    im = Image.composite(Image.new('RGB', (W, H), (242, 169, 59)), im, glow.point(lambda v: v * .35)); d = ImageDraw.Draw(im)
    cjk = f'{REPO}/app/src/main/res/font/noto_sans_sc_700.ttf'; lat = f'{REPO}/app/src/main/res/font/schibsted_grotesk_700.ttf'; mono = f'{REPO}/app/src/main/res/font/ibm_plex_mono_500.ttf'
    im.paste(ico.resize((120, 120), Image.LANCZOS), (80, 96), ico.resize((120, 120), Image.LANCZOS))
    if lang == 'zh':
        d.text((80, 250), '理照', font=ImageFont.truetype(cjk, 112), fill=(238, 233, 226))
        d.text((80, 396), '一间装在口袋里的暗房', font=ImageFont.truetype(cjk, 46), fill=(242, 169, 59))
        d.text((80, 470), '上滑收藏 · 下滑移走 · 纯本地 · 开源', font=ImageFont.truetype(cjk, 30), fill=(140, 133, 125))
    else:
        d.text((80, 250), 'CleanPhotos', font=ImageFont.truetype(lat, 104), fill=(238, 233, 226))
        d.text((80, 396), 'A darkroom in your pocket', font=ImageFont.truetype(lat, 46), fill=(242, 169, 59))
        d.text((80, 470), 'Swipe up to keep · down to trash · on-device · open source', font=ImageFont.truetype(lat, 28), fill=(140, 133, 125))
    d.text((80, 548), 'cleanphotos.winters.click', font=ImageFont.truetype(mono, 24), fill=(91, 85, 79))
    for k, (x, y, r) in zip(('compare', 'favorite'), ((780, 40, -6), (960, 90, 5))):
        s = Image.open(f'{S}/shots/{k}.webp').convert('RGB').resize((216, 480)); fr = Image.new('RGBA', (232, 496), (0, 0, 0, 255)); fr.paste(s, (8, 8))
        m = Image.new('L', fr.size, 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, 231, 495), 30, fill=255); fr.putalpha(m); fr = fr.rotate(r, expand=True, resample=Image.BICUBIC)
        im.paste(fr, (x, y), fr)
    im.save(f'{S}/img/og-{lang}.jpg', quality=86)
og('zh'); og('en')
for root, _, fs in os.walk(S):
    for f in sorted(fs): p = os.path.join(root, f); print(f'{os.path.getsize(p) / 1024:8.0f} KB  {os.path.relpath(p, SITE)}')

# 首屏晾片绳上的小拍立得：240×240 方形缩略图
from PIL import Image, ImageOps
for f in ['p186', 'hero', 'p219', 'a', 'p164', 'g']:
    ImageOps.fit(Image.open(f'{S}/photos/{f}.webp').convert('RGB'), (240, 240), Image.LANCZOS).save(f'{S}/photos/print-{f}.webp', quality=72)
