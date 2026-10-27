# 理照官网 · cleanphotos.winters.click

Cloudflare Workers + 静态资源。下载由 Cloudflare 直接提供，访问时不依赖 GitHub。

```
site/
├── wrangler.jsonc          Worker 配置：自定义域名 cleanphotos.winters.click，静态资源目录 dist/
├── src/worker.js           /download/*、/api/*、首页语言跳转；其余交给静态资源
├── build.mjs               static/ + release/ + ../CHANGELOG.md + ../PRIVACY.md → dist/
├── static/                 提交进仓库的素材：css、js、字体、宣传片、截图、示例照片、图标
├── tools/                  make-media.py（重新生成 static/ 素材）、shoot.mjs（截图）、record-demo.mjs（录屏）
├── release/                （不提交）scripts/site-sync-release.sh 拉下来的 APK + releases.json
├── dist/                   （不提交）构建产物
└── PROPOSED-release-workflow.patch   发版流水线改动提案（未应用）
```

旧的单页 `site/index.html` 已删除；原 `site/assets/photos/` 移到 `promo/assets/photos/`（宣传片和 `tools/make-media.py` 的素材源），图标统一用 `site/static/img/icon.svg`。

## 地址

| 地址 | 内容 |
| --- | --- |
| `/` | 中文首页；没选过语言且浏览器首选不是中文时 302 到 `/en/`（手动切换后记在 `lang` Cookie） |
| `/en/` · `/changelog/` · `/en/changelog/` · `/privacy/` · `/en/privacy/` | 英文首页、更新日志、隐私政策 |
| `/download/latest`、`/download/CleanPhotos-latest.apk` | 最新版 APK，文件名 `CleanPhotos-x.y.z.apk`，`Cache-Control: no-cache` |
| `/download/CleanPhotos-x.y.z.apk` | 指定版本；官网保留最近 `KEEP`（默认 2）个版本，更早的 302 到 GitHub Release |
| `/api/latest.json` | 最新版：version、file、size、sha256、certSha256、date、url、github、notes.zh / notes.en |
| `/api/releases.json` | 官网保存的全部版本 |

## 本地预览

```bash
scripts/site-sync-release.sh     # 在仓库根目录：拉最近 2 个正式版 APK，核对 sha256 和签名证书
cd site && npm ci && npm run build
npx wrangler dev                 # http://localhost:8787
```

## 第一次部署（手动，一次性）

1. `cd site && npx wrangler login`（或用已经登录的全局 wrangler），账号 ID `7b6d1ba7fb164a228c33fd9768b0fe65` 已写在 `wrangler.jsonc`。
2. `scripts/site-sync-release.sh && cd site && npm run build && npx wrangler deploy`。
   第一次部署会创建 Worker `cleanphotos-site`，并把 `cleanphotos.winters.click` 挂成 Workers Custom Domain：Cloudflare 自动建 DNS 记录和证书，不需要手动加 DNS。这个主机名上不能已经有 CNAME 记录。
3. 打开 https://cleanphotos.winters.click/api/latest.json 确认版本。

## 发版时自动更新（应用 PROPOSED-release-workflow.patch 之后）

`git apply site/PROPOSED-release-workflow.patch`，然后在 GitHub 仓库 Settings → Secrets and variables → Actions 加两个 secret：

- `CLOUDFLARE_ACCOUNT_ID` = `7b6d1ba7fb164a228c33fd9768b0fe65`
- `CLOUDFLARE_API_TOKEN`：Cloudflare → My Profile / Manage Account → API Tokens → Create Token → Custom token，权限只给：
  - Account · Workers Scripts · Edit（账号 7b6d1ba7…）
  - Zone · Workers Routes · Edit（只限 zone `winters.click`）
  - Account · Account Settings · Read

Worker 已经存在（第 1 步手动部署过）时这些权限就够；如果想让 CI 从零创建 Worker，需要更高的 Workers Admin 权限，不建议。

发版流程：`apk` 任务照旧构建并发布 GitHub Release，然后把 APK 作为 artifact 交给新的 `site` 任务；`site` 任务核对 sha256 / 签名证书、构建并 `wrangler deploy`（以 wrangler 成功并输出 `Current Version ID` 为准，版本号写进任务摘要），最后用 `scripts/site-check-live.sh` 补充核对线上 `/api/latest.json`：重试几次，拿到 JSON 且版本不对才失败；Cloudflare 拦 GitHub runner（403 / 挑战页 / 非 JSON）只标 warning，需要时在本机 `curl` 核对。`site` 任务设了 `continue-on-error`，失败只标黄，不影响已经发布的 Release；没配 secret 时直接跳过。
