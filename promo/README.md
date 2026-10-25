# 宣传片与主页

## 宣传片（`promo/video/`）

画面是一个 1920×1080 的 HTML 舞台，每一帧都是时间 t 的纯函数：`seek(t)` 摆好画面，`render.mjs` 逐帧截图交给 ffmpeg，再混入配乐。视觉与动效数值来自 `docs/ui-design.md` 与 `core/design`（暗房）。

依赖：Node、本机 Google Chrome、ffmpeg（带 libx264）。配乐 `promo/music/ethernight-club.mp3` 随仓库提交（CC BY，见下方授权）。

渲染产物不入库：`promo/out/`（成片、静帧、srt、海报和网页压缩版 `promo.mp4`）。发版时把 `out/cleanphotos-promo.clean.mp4` 和三份 srt 挂到 GitHub Release，同时上传 B 站 / YouTube。主页和根 README 链到这两个地址。

```bash
cd promo && npm install
node video/render.mjs                    # 成片 → out/cleanphotos-promo.mp4，网页版 → out/promo.mp4，海报 → out/promo-poster.jpg，字幕
node video/render.mjs --clean            # 不烧录字幕的版本 → out/cleanphotos-promo.clean.mp4（上传 B 站 / YouTube 时配 srt）
node video/render.mjs --still 12,35.5    # 只截几帧 PNG 检查画面
node video/render.mjs --from 30 --to 40  # 只渲染一段（无配乐）
```

- 字幕在 `video/cues.js`，改这一处，烧录字幕和导出的 `out/*.srt`（中文 / 英文 / 中英双语）一起变。srt 由渲染重新生成，不另存进 git。
- 镜头按配乐节拍排：117 BPM，16.41s 鼓点进入；时间轴常量都在 `video/index.html` 的脚本里（`ACTS`、`MAIN` 等）。
- 浏览器预览：在仓库根目录起一个静态服务（如 `python3 -m http.server`），打开 `/promo/video/index.html`，点画面播放 / 暂停（带配乐），`?t=12.3` 停在某一秒。

## 主页（`site/`）

官网 https://cleanphotos.winters.click 部署在 Cloudflare Workers 上，构建、预览和部署见 [`site/README.md`](../site/README.md)。官网首屏用的是 `promo/product/` 的产品宣传片（压缩版在 `site/static/media/`，由 `site/tools/make-media.py` 生成）。

## 素材授权

- 配乐：“Ethernight Club” Kevin MacLeod (incompetech.com)
  Licensed under Creative Commons: By Attribution 4.0 License
  http://creativecommons.org/licenses/by/4.0/
  发布视频时需在简介里保留这段署名（片尾和主页页脚已带）。
- 照片：见 `promo/assets/photos/CREDITS.md`（`p*.jpg` 为 Unsplash License；`a–g.jpg`、`hero.jpg` 为 AI 生成图片，无版权，可自由使用）。
- 图标：`site/static/img/icon.svg` 由 `app/src/main/res/drawable/ic_launcher_*.xml` 转换。
