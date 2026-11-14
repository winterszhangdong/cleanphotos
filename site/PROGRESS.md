# 官网（Cloudflare Workers）进度

起于 2026-10-24 21:45 UTC+8。不部署、不提交、不放密钥。

- [x] 摸底：site/ 里已有旧的静态页（site/index.html + site/assets，已入库；README 和 promo 引用 site/assets/*），保留不动；新站放 site/ 下的新文件（wrangler.jsonc、src/、pages/、public/ 由构建生成）
- [x] GitHub 最新版已是 v1.5.0（2026-10-24 21:02 UTC+8，4,018,524 B，sha256 9967a908…），v1.4.0 sha256 c9073668…
- [x] 同步脚本 scripts/site-sync-release.sh（21:48 UTC+8 跑通：1.5.0、1.4.0 两个 APK，sha256 和签名证书都对上）
- [ ] Worker + 构建脚本
- [ ] 页面：首页 / 更新日志 / 隐私政策，中英
- [ ] 截图、录屏、Lighthouse
- [ ] 工作流补丁 site/PROPOSED-release-workflow.patch
- 21:48 父任务更新：域名 cleanphotos.winters.click（Workers Custom Domain），account_id 7b6d1ba7fb164a228c33fd9768b0fe65；本机全局 wrangler 4.112 已登录，只用 wrangler dev，不 deploy、不建 DNS
- [x] 媒体（21:52 UTC+8）：tools/make-media.py → static/（拉丁字体 woff2、宣传片 576×1024 2.3 MB、海报、10 张真实 App 截图去掉录屏状态栏、演示照片 webp、图标、分享卡片）
- [x] Worker + 构建脚本（22:00 UTC+8）：wrangler.jsonc（自定义域名 cleanphotos.winters.click，compatibility_date 2026-07-15，全局 wrangler 4.112 只支持到 2026-07-21）、src/worker.js、build.mjs。wrangler dev 实测：/download/latest 与 /download/CleanPhotos-latest.apk → 1.5.0 sha256 9967a908…一致；/download/CleanPhotos-1.4.0.apk → c9073668…一致；1.2.0 → 302 GitHub；/apk/* 直接访问 404；/ 按 Accept-Language / lang Cookie 跳转。
- [x] 页面：首页 / 更新日志 / 隐私政策，中英（22:00 UTC+8，待截图检查）
- [x] 截图 / Lighthouse（22:15 UTC+8）：tools/shoot.mjs、tools/record-demo.mjs（CDP 录屏），整页图用 1 倍像素（Chrome 单张 16384 像素上限）。Lighthouse 手机：首页 95/100/100/100（LCP 2.9 s）。修过：首屏视频推迟到 load 后 2.5 s 空闲再挂源；晾片绳缩略图 240px；图片 height:auto；顶栏下载按钮 aria-label。
- [x] 工作流补丁（22:20 UTC+8）：site/PROPOSED-release-workflow.patch（git apply --check 通过，未应用）。apk 任务末尾上传 artifact；新 site 任务 needs: apk + continue-on-error，没配 secret 就跳过；sync → build → wrangler deploy → 核对线上 /api/latest.json。CI 模式的 sync 脚本本地实测通过。
- [x] site/README.md：结构、地址表、首次手动部署、token 权限。
- [x] 最终截图 / 录屏 / Lighthouse（22:25 UTC+8）：首页 中 95/100/100/100、英 93/100/100/100；更新日志 98/100/100/100；隐私 100/100/100/100。产物在 site/shots-out/（已加入 .gitignore）。
- [x] 删除旧单页（22:30 UTC+8，已获同意）：git rm site/index.html、site/assets/icon.svg、promo-poster.jpg；site/assets/photos → promo/assets/photos（git mv）；引用改到 site/static/ 与 promo/assets/。
- [x] 下载区精简（22:30 UTC+8，用户反馈）：去掉「安装包 SHA-256」「签名证书 SHA-256」两行和复制按钮、FAQ「怎么确认安装包没有被改过」、安装 FAQ 里提到校验值的一句；删掉对应 JS/CSS。页面上已无 sha256 / 证书文字，/api/latest.json 仍保留 sha256。截图 shots-out/site-download-v2.png。
- [x] 用户反馈三处（22:45 UTC+8）：① 首屏视频加暂停 / 播放键（静音键旁，可键盘聚焦，点视频也能切换），手动暂停后滚回视野不自动续播；② 隐私段落降级为小标题 + 5 个小卡片（一句说明），去掉大字墙；③ 触屏演示默认不接管手势（touch-action: pan-y）+「点一下，上手试试」，激活后才接管，「完成」退出，滚出视野 / 看完小结 / 8 秒无操作自动退出；手机上模拟手机最高 62% 视口高度。tools/test-demo-touch.mjs 18 项全部通过（含桌面滚轮）。录屏 shots-out/site-demo-v2.mp4。Lighthouse 首页中文 94/100/100/100（LCP 3.0 s）。
- [x] 顶栏 GitHub 按钮（22:45 UTC+8，用户反馈）：所有页面中英文顶栏加 GitHub 链接（新标签页、rel=noopener、aria-label「GitHub 源码」/「Source on GitHub」），桌面图标 + 文字，≤560px 只留 36px 圆形图标；不取 star 数。360px 宽无溢出。截图 shots-out/site-header-github.png。
- [x] 工作流补丁已应用到工作区（10-25 12:15 UTC+8，用户已加 secrets）：.github/workflows/release.yml +70 行，未提交。actionlint（docker rhysd/actionlint）通过；修了一个 shellcheck 提示（循环变量 i → _），补丁文件同步更新并保留。Worker cleanphotos-site 已存在（10-24 22:42 UTC+8 首次部署），线上 /api/latest.json 200。
