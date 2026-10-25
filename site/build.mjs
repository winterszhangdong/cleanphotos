// 理照官网构建：static/ + release/（APK 与 releases.json）+ 仓库里的 CHANGELOG.md / PRIVACY.md → dist/
// 用法：node build.mjs   （先跑 ../scripts/site-sync-release.sh 准备 release/）
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const REPO_DIR = path.resolve(ROOT, '..');
const DIST = path.join(ROOT, 'dist');
const ORIGIN = 'https://cleanphotos.winters.click';

const rel = JSON.parse(fs.readFileSync(path.join(ROOT, 'release/releases.json'), 'utf8'));
const REPO = rel.repo;
const changelog = parseChangelog(fs.readFileSync(path.join(REPO_DIR, 'CHANGELOG.md'), 'utf8'));
const privacy = parsePrivacy(fs.readFileSync(path.join(REPO_DIR, 'PRIVACY.md'), 'utf8'));

// ---------- 工具 ----------
const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const mb = n => (n / 1048576).toFixed(1) + ' MB';
const day = iso => new Date(iso).toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' });
const hash = f => crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex').slice(0, 10);
const write = (p, s) => { const f = path.join(DIST, p); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, s); };

function inline(s) {
  let out = esc(s);
  out = out.replace(/`([^`]+)`/g, (_, c) => `<code>${c}</code>`);
  out = out.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  out = out.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, t, u) => `<a href="${u}">${t}</a>`);
  out = out.replace(/&lt;(https?:\/\/[^&\s]+)&gt;/g, '<a href="$1">$1</a>');
  return out;
}
// 只覆盖 CHANGELOG / PRIVACY 用到的语法：### 标题、多级 - 列表（含续行）、段落、行内代码 / 粗体 / 链接
function md(src, { headings = {} } = {}) {
  const lines = src.replace(/\r/g, '').split('\n');
  let html = '', stack = [], para = [];
  const flushPara = () => { if (para.length) { html += `<p>${inline(para.join(' '))}</p>\n`; para = []; } };
  const closeTo = depth => { while (stack.length > depth) { html += '</li></ul>'; stack.pop(); } };
  for (const line of lines) {
    const h = line.match(/^(#{2,4})\s+(.*)$/);
    const li = line.match(/^(\s*)[-*]\s+(.*)$/);
    if (h) { flushPara(); closeTo(0); const t = headings[h[2].trim()] || h[2]; html += `<h${h[1].length + 0}>${inline(t)}</h${h[1].length}>\n`; continue; }
    if (li) {
      flushPara();
      const depth = Math.floor(li[1].length / 2) + 1;
      if (depth > stack.length) { while (stack.length < depth) { html += '<ul><li>'; stack.push(1); } }
      else { closeTo(depth); html += '</li><li>'; }
      html += inline(li[2]);
      continue;
    }
    if (!line.trim()) { flushPara(); continue; }
    if (stack.length && /^\s+/.test(line)) { html += ' ' + inline(line.trim()); continue; }
    closeTo(0); para.push(line.trim());
  }
  flushPara(); closeTo(0);
  return html;
}

function parseChangelog(src) {
  src = src.replace(/\r/g, '').replace(/^\[[^\]]+\]:\s+\S+\s*$/gm, '');
  const out = [];
  const re = /^## \[([^\]]+)\](?:\s*-\s*(\S+))?\s*$/gm;
  const marks = [...src.matchAll(re)];
  marks.forEach((m, i) => {
    if (m[1].toLowerCase() === 'unreleased') return;
    const body = src.slice(m.index + m[0].length, i + 1 < marks.length ? marks[i + 1].index : undefined).trim();
    const parts = body.split(/^---\s*$/m);
    out.push({ version: m[1], date: m[2] || '', zh: parts[0].trim(), en: (parts[1] || parts[0]).trim() });
  });
  return out;
}
function parsePrivacy(src) {
  src = src.replace(/\r/g, '');
  const en = src.split(/^## English\s*$/m)[1].split(/^## 简体中文\s*$/m)[0].trim();
  const zh = src.split(/^## 简体中文\s*$/m)[1].trim();
  return { en, zh };
}

// ---------- 复制静态资源和 APK ----------
fs.rmSync(DIST, { recursive: true, force: true });
fs.cpSync(path.join(ROOT, 'static'), DIST, { recursive: true });
fs.mkdirSync(path.join(DIST, 'apk'), { recursive: true });
for (const v of rel.versions) {
  const src = path.join(ROOT, 'release', v.file);
  const got = crypto.createHash('sha256').update(fs.readFileSync(src)).digest('hex');
  if (got !== v.sha256) throw new Error(`${v.file}: sha256 mismatch`);
  fs.copyFileSync(src, path.join(DIST, 'apk', v.file));
}
const latest = rel.versions.find(v => v.version === rel.latest);
const notesOf = ver => changelog.find(c => c.version === ver) || { zh: '', en: '' };
write('api/releases.json', JSON.stringify({
  repo: REPO, certSha256: rel.certSha256, latest: rel.latest,
  versions: rel.versions.map(v => ({
    version: v.version, file: v.file, size: v.size, sha256: v.sha256, certSha256: rel.certSha256, date: v.date,
    url: `${ORIGIN}/download/${v.file}`, latestUrl: `${ORIGIN}/download/latest`, github: v.github,
    notes: { zh: notesOf(v.version).zh, en: notesOf(v.version).en },
  })),
}, null, 2) + '\n');

const CSS = `/css/site.css?v=${hash(path.join(DIST, 'css/site.css'))}`;
const JS = `/js/site.js?v=${hash(path.join(DIST, 'js/site.js'))}`;

// ---------- 文案 ----------
const L = {
  zh: {
    html: 'zh-CN', og: 'zh_CN', base: '', other: 'en', otherLabel: 'EN', otherName: 'English',
    name: '理照', title: '理照 CleanPhotos · 一间装在口袋里的暗房', desc: '理照是一款开源的安卓相册清理 App：一次只看一张，上滑收藏、下滑移走，相似照片挑着留，照片视频一起理。纯本地、不联网、无广告、无账号。',
    nav: ['试一试', '功能', '隐私', '下载', '更新日志'], getShort: '下载',
    skip: '跳到正文',
    kicker: '安卓相册清理 · 开源免费',
    h1: '<span class="line">一间装在口袋里的</span><span class="line"><em>暗房</em></span>',
    lede: '照片是唯一的光。一次只看一张，<b>上滑收藏，下滑移走</b>，4 秒内随时撤回。相似的几张挑着留，大文件和截图一眼看清。照片从不离开你的手机。',
    dl: '下载 Android 版', src: '在 GitHub 查看源码',
    meta: ['Android 8.0 及以上', '免费', '无广告', '无需账号'],
    videoLabel: '理照产品演示视频', play: '播放演示视频', soundOn: '打开声音', soundOff: '静音',
    tryK: '亲手滑一下', tryH: '每一次滑动，<br>都是一个<em>决定</em>',
    trySub: '这是清理页的网页版。阈值、回弹、琥珀余影和撤回倒计时，都照着 App 做。',
    keys: [['↑', '上滑：收藏，琥珀光留下余影', 'up'], ['↓', '下滑：移到 App 内垃圾桶', ''], ['← →', '左右：翻到上一张、下一张', ''], ['Z', '撤回刚才那一下', '']],
    demoLabel: '可交互的清理页演示：上滑收藏，下滑移到垃圾桶，左右翻页，Z 撤回',
    rvDate: '9月21日', weekday: '周一', upKeep: '上滑收藏', downTrash: '下滑移走',
    cueUpPre: '继续上滑收藏', cueUpPost: '松手收藏', cueDownPre: '继续下滑', cueDownPost: '松手移到垃圾桶',
    undo: '撤回', doneK: '9月21日 · 周一', doneH: '这一天整理完了', dF: '收藏', dT: '移走', dK: '保留', again: '再理一遍',
    tallyF: '收藏', tallyT: '移走', demoStart: '点一下，上手试试', demoExit: '完成',
    T: { kept: '已收藏', trashed: '已移到垃圾桶', keptSub: '9月21日 · 第 %d 张', trashedSub: '可释放 %s MB', weekday: '周一', soundOn: '打开声音', soundOff: '静音', pause: '暂停视频', play: '播放视频' },
    featK: '功能', featH: '理相册该有的，<br>一样不<em>多</em>，一样不少',
    featSub: '没有「一键清理」，也不替你做决定。理照只把照片按日期摆好，把相似的放在一起，把占地方的挑出来，剩下的交给你的手指。',
    feats: [
      { no: '01', h: '相似照片，挑着留', p: '连拍和相近的几张自动成组。点开全屏对比，放大后切换照片位置不变，方便逐张看细节。默认推荐最清晰的一张；上滑留下，下滑移走，一组可以留好几张，也可以全部移走。', shots: ['compare', 'groups'], tag: ['默认推荐', '最清晰的一张'], alt: '相似照片对比页截图' },
      { no: '02', h: '空间去哪了，<br>一眼看清', p: '照片页顶部一排常驻的范围按钮，一点就到。大号数字显示当前范围的张数，大文件、截图、聊天软件存图还会显示总大小。「只看没看过」可以和任何范围叠加。', shots: ['big', 'shots'], chips: ['全部', '相似', '大文件', '截图', '聊天', '那年今天'], on: 2, alt: '照片页大文件范围截图' },
      { no: '03', h: '上滑收藏，<br>下滑移走', p: '一次只看一张，全屏，没有多余的按钮。收藏时琥珀色的余影向上飘走，移走的照片褪成灰。每一下都能在 4 秒内撤回，左右滑可以先跳过。', shots: ['favorite', 'grid'], tag: ['撤回倒计时', '4 秒'], alt: '清理页上滑收藏截图' },
      { no: '04', h: '照片和视频，<br>一起理', p: '视频和照片按日期排在一起，同样收藏、移走、撤回；清理页停稳后静音播放一遍。动态照片也认得出来，会先动一下再停在静态图。', shots: ['video'], chips: ['Google', '小米', 'vivo', 'OPPO', '三星', '荣耀', '华为'], chipsLabel: '支持这些品牌的动态照片', alt: '清理页播放视频截图' },
      { no: '05', h: '垃圾桶，<br>给你反悔的机会', p: '移走只是在 App 里做个记号，照片还在相册原位，随时可以移出垃圾桶。只有清空垃圾桶并在系统弹窗里确认，才会真正删除。还能看到累计已经腾出了多少空间。', shots: ['trash', 'freed'], tag: ['清空前', '随时可以移出'], alt: '垃圾桶页面截图' },
    ],
    extras: [
      ['i-day', '一天一结', '一天至少 6 张且全部看完后，会插入一页当天小结：收藏、移走、保留各几张。'],
      ['i-multi', '长按多选', '在照片页长按进入多选，拖动连选、按天全选，批量收藏或移到垃圾桶，也能整批撤回。'],
      ['i-lang', '中文 / English', '默认跟随系统语言，也可以在 App 里随时切换；日期、数字和文件大小按语言格式化。'],
    ],
    privK: '隐私', privH: '照片只留在你的手机里',
    privWords: [['纯本地', '整理都在手机上完成'], ['不联网', '没有申请联网权限'], ['无广告', '也没有统计和崩溃上报'], ['无账号', '装上就能用'], ['开源', '代码全部公开，Apache 2.0']],
    privMore: '照片不会被复制或上传，收藏和垃圾桶记录只存在本机。', privLink: '阅读隐私政策',
    dlK: '下载', dlH: '装上它，<br>今晚就理一理',
    apkName: '理照 · Android', ver: v => `<b>v${v.version}</b> · ${day(v.date)} 发布 · ${mb(v.size)}`, dlBtn: '下载 APK', gh: '也可以从 <a href="%s">GitHub Releases</a> 下载',
    req: 'Android 8.0（API 26）及以上', changelogLink: '看看这一版改了什么',
    iosName: '理照 · iOS', iosPill: '暂无安装包', iosP: 'iOS 版用 SwiftUI 编写，iOS 17 及以上，功能和交互与安卓版对齐。目前还没有上架，需要用 Xcode 从源码构建安装到自己的 iPhone。', iosBtn: '查看 iOS 源码与构建说明',
    dlNote: '下载地址 <code>/download/latest</code> 始终指向最新版，GitHub 发版后会同步更新。',
    faqK: '常见问题', faqH: '装之前，<br>你可能想知道',
    faq: [
      ['提示「禁止安装未知应用」怎么办？', ['理照目前不在应用商店上架，安卓对商店以外的安装包都会这样提示。在弹窗里点「设置」，允许当前浏览器（或文件管理器）安装未知应用，再返回点一次安装即可。', '有些手机还会做一次安全扫描或要求登录账号，按提示继续就行。']],
      ['以后怎么升级？', ['回到这里下载新版，直接安装覆盖旧版即可。签名不变，收藏、垃圾桶和看过的记录都会保留。', '每一版改了什么，可以看<a href="%c">更新日志</a>。']],
      ['需要哪些权限？', ['只需要读取照片和视频的权限。Android 14 及以上可以只授权部分照片；Android 8–10 第一次清空垃圾桶时，会申请存储写入权限，用来删除你确认过的照片。', '理照没有申请联网权限，详细说明见<a href="%p">隐私政策</a>。']],
      ['移到垃圾桶的照片还能找回吗？', ['能。移到垃圾桶只是在 App 里做记录，照片还在相册里原来的位置。在垃圾桶里选中照片「移出垃圾桶」，它就回到照片页。只有清空垃圾桶并确认后，照片才会被删除。']],
      ['iPhone 能用吗？', ['有 iOS 版，但还没有上架，也没有安装包。会用 Xcode 的话，可以从源码构建装到自己的 iPhone（iOS 17 及以上）。']],
    ],
    finalH: '把光，留给<em>值得</em>的照片', 
    foot: ['更新日志', '隐私政策', 'GitHub', '问题反馈'],
    credit: `理照 CleanPhotos 是开源软件，以 Apache License 2.0 发布。网站上的示例照片来自 Unsplash（<a href="/photos/CREDITS.md">致谢</a>），截图为 App 实拍；字体 Schibsted Grotesk、IBM Plex Mono 以 SIL Open Font License 授权。`,
    clTitle: '更新日志', clDesc: '理照每个版本的新增、改动和修复。', clLead: '每个版本新增、改动和修复了什么。完整的提交记录见 <a href="%s">GitHub</a>。', clLatest: '最新', clDl: '下载这一版', clHead: { Added: '新增', Changed: '改动', Fixed: '修复', Removed: '移除', Security: '安全', Deprecated: '弃用' },
    pvTitle: '隐私政策', pvDesc: '理照不联网、不收集任何数据。这里说明 App 申请的权限和保存在本机的数据。', pvSite: '关于本网站', pvSiteP: '本网站托管在 Cloudflare 上，不使用 Cookie 追踪、统计脚本或第三方字体；只有你手动切换语言时，会在浏览器里存一个记住语言的 Cookie。Cloudflare 作为托管方可能按其政策记录访问日志。',
    nfTitle: '找不到这一页', nfP: '这张底片好像没冲出来。', nfBack: '回到首页',
  },
  en: {
    html: 'en', og: 'en_US', base: '/en', other: 'zh', otherLabel: '中文', otherName: '简体中文',
    name: 'CleanPhotos', title: 'CleanPhotos · A darkroom in your pocket', desc: 'CleanPhotos is an open-source photo cleaner for Android. One photo at a time: swipe up to keep, down to trash. Pick the keepers from similar shots, sort photos and videos together. Local only, offline, no ads, no account.',
    nav: ['Try it', 'Features', 'Privacy', 'Download', 'Changelog'], getShort: 'Download',
    skip: 'Skip to content',
    kicker: 'Photo cleanup for Android · Free & open source',
    h1: '<span class="line">A <em>darkroom</em></span><span class="line">in your pocket</span>',
    lede: 'Your photos are the only light. One at a time: <b>swipe up to keep, down to trash</b>, undo within 4 seconds. Pick the keepers from similar shots and see what eats your storage. Your photos never leave your phone.',
    dl: 'Download for Android', src: 'View source on GitHub',
    meta: ['Android 8.0+', 'Free', 'No ads', 'No account'],
    videoLabel: 'CleanPhotos product video', play: 'Play the product video', soundOn: 'Turn sound on', soundOff: 'Mute',
    tryK: 'Try it', tryH: 'Every swipe<br>is a <em>decision</em>',
    trySub: 'A web version of the Review screen. The threshold, the spring back, the amber afterglow and the undo countdown all follow the app.',
    keys: [['↑', 'Up: keep it, with an amber afterglow', 'up'], ['↓', 'Down: move it to the in-app trash', ''], ['← →', 'Left / right: previous or next photo', ''], ['Z', 'Undo the last swipe', '']],
    demoLabel: 'Interactive Review screen demo: swipe up to keep, down to trash, left or right to browse, Z to undo',
    rvDate: 'Mon, Sep 21', weekday: 'Mon', upKeep: 'Up: keep', downTrash: 'Down: trash',
    cueUpPre: 'Keep pushing to keep', cueUpPost: 'Release to keep', cueDownPre: 'Keep pulling', cueDownPost: 'Release to trash',
    undo: 'Undo', doneK: 'Mon, Sep 21', doneH: 'Day complete', dF: 'Kept', dT: 'Trashed', dK: 'Left as is', again: 'Do it again',
    tallyF: 'kept', tallyT: 'trashed', demoStart: 'Tap to try', demoExit: 'Done',
    T: { kept: 'Kept', trashed: 'Moved to trash', keptSub: 'Sep 21 · photo %d', trashedSub: 'Frees %s MB', weekday: 'Mon', soundOn: 'Turn sound on', soundOff: 'Mute', pause: 'Pause video', play: 'Play video' },
    featK: 'Features', featH: 'Everything sorting needs.<br>Nothing it <em>doesn’t</em>.',
    featSub: 'No “clean everything” button, and no decisions made for you. CleanPhotos lays your photos out by date, groups the similar ones, surfaces the big ones, and leaves the rest to your thumb.',
    feats: [
      { no: '01', h: 'Similar shots: keep the best', p: 'Bursts and near-duplicates are grouped automatically. Compare them full screen; the zoom stays put as you switch, so you can check details. The sharpest one is suggested. Swipe up to keep, down to remove, and keep as many as you like, or none.', shots: ['compare', 'groups'], tag: ['Suggested', 'the sharpest one'], alt: 'Screenshot of the similar-photo compare page' },
      { no: '02', h: 'See where<br>your space went', p: 'A row of scope buttons sits under the header, one tap each. The big number shows how many photos are in scope, plus total size for large files, screenshots and chat app images. “Unseen only” combines with any scope.', shots: ['big', 'shots'], chips: ['All', 'Similar', 'Large files', 'Screenshots', 'Chat', 'On this day'], on: 2, alt: 'Screenshot of the Photos page showing large files' },
      { no: '03', h: 'Swipe up to keep,<br>down to trash', p: 'One photo at a time, full screen, no extra buttons. Keeping sends an amber afterglow upward; trashed photos fade to gray. Every swipe can be undone within 4 seconds, and you can skip with a left or right swipe.', shots: ['favorite', 'grid'], tag: ['Undo countdown', '4 seconds'], alt: 'Screenshot of keeping a photo on the Review screen' },
      { no: '04', h: 'Photos and videos,<br>together', p: 'Videos sit with your photos by date and can be kept, trashed and undone the same way; on the Review screen they play once, muted, after the page settles. Motion photos are recognized too: they move once, then rest on the still.', shots: ['video'], chips: ['Google', 'Xiaomi', 'vivo', 'OPPO', 'Samsung', 'Honor', 'Huawei'], chipsLabel: 'Motion photos from these brands', alt: 'Screenshot of a video playing on the Review screen' },
      { no: '05', h: 'A trash can<br>that lets you change your mind', p: 'Trashing only marks a photo in the app; it stays where it is in your gallery, and you can restore it any time. Photos are deleted only when you empty the trash and confirm in the system prompt. You can also see how much space you have freed in total.', shots: ['trash', 'freed'], tag: ['Until you empty it', 'restore any time'], alt: 'Screenshot of the Trash page' },
    ],
    extras: [
      ['i-day', 'A summary for each day', 'Finish a day with at least 6 photos and a summary page appears: how many you kept, trashed and left as is.'],
      ['i-multi', 'Long-press to select', 'Long-press on the Photos page, drag to select more or select a whole day, then keep or trash in bulk, and undo the whole batch.'],
      ['i-lang', 'English / 中文', 'Follows your system language, or switch inside the app. Dates, numbers and file sizes are formatted to match.'],
    ],
    privK: 'Privacy', privH: 'Your photos stay on your phone',
    privWords: [['Local only', 'Everything happens on your phone'], ['Offline', 'No network permission'], ['No ads', 'And no analytics or crash reporting'], ['No account', 'Install and go'], ['Open source', 'All code public, Apache 2.0']],
    privMore: 'Photos are never copied or uploaded; favorites and trash records live only on your device.', privLink: 'Read the privacy policy',
    dlK: 'Download', dlH: 'Install it.<br>Sort tonight.',
    apkName: 'CleanPhotos · Android', ver: v => `<b>v${v.version}</b> · released ${day(v.date)} · ${mb(v.size)}`, dlBtn: 'Download APK', gh: 'Also on <a href="%s">GitHub Releases</a>',
    req: 'Android 8.0 (API 26) or later', changelogLink: 'See what’s new in this version',
    iosName: 'CleanPhotos · iOS', iosPill: 'No build to download yet', iosP: 'The iOS version is written in SwiftUI for iOS 17 and later, with the same features and interactions as Android. It is not on the App Store yet; build it from source with Xcode to install it on your own iPhone.', iosBtn: 'iOS source and build notes',
    dlNote: '<code>/download/latest</code> always points to the newest version and updates when a new release is published on GitHub.',
    faqK: 'FAQ', faqH: 'Before you<br>install',
    faq: [
      ['It says installing unknown apps is blocked. What now?', ['CleanPhotos is not in an app store yet, and Android shows this for any app installed from outside one. Tap “Settings” in the prompt, allow your browser (or file manager) to install unknown apps, then go back and tap install again.', 'Some phones also run a security scan or ask you to sign in; just follow the prompts.']],
      ['How do I update?', ['Download the new version here and install it over the old one. The signature stays the same, so your favorites, trash and seen records are kept.', 'See the <a href="%c">changelog</a> for what changed in each version.']],
      ['What permissions does it need?', ['Only access to your photos and videos. On Android 14+ you can allow selected photos only. On Android 8–10 it asks for storage write access the first time you empty the trash, so it can delete the photos you confirmed.', 'CleanPhotos does not request network access. Details are in the <a href="%p">privacy policy</a>.']],
      ['Can I get back photos I moved to the trash?', ['Yes. Moving to the trash only records it in the app; the photo stays where it was in your gallery. Select it in the trash and tap “Restore” to bring it back to Photos. Photos are deleted only after you empty the trash and confirm.']],
      ['Is there an iPhone version?', ['There is an iOS version, but it is not on the App Store and there is no build to download. If you use Xcode, you can build it from source for your own iPhone (iOS 17+).']],
    ],
    finalH: 'Keep the light for the photos that <em>matter</em>',
    foot: ['Changelog', 'Privacy', 'GitHub', 'Report an issue'],
    credit: `CleanPhotos is open-source software released under the Apache License 2.0. Sample photos on this site are from Unsplash (<a href="/photos/CREDITS.md">credits</a>); screenshots are from the app. Fonts: Schibsted Grotesk and IBM Plex Mono, under the SIL Open Font License.`,
    clTitle: 'Changelog', clDesc: 'What was added, changed and fixed in each CleanPhotos release.', clLead: 'What was added, changed and fixed in each release. Full history on <a href="%s">GitHub</a>.', clLatest: 'Latest', clDl: 'Download this version', clHead: {},
    pvTitle: 'Privacy policy', pvDesc: 'CleanPhotos works offline and collects no data. The permissions it asks for and what it stores on your device.', pvSite: 'About this website', pvSiteP: 'This website is hosted on Cloudflare and uses no tracking cookies, analytics scripts or third-party fonts. If you switch languages manually, your browser stores one cookie to remember the choice. As the host, Cloudflare may keep access logs under its own policy.',
    nfTitle: 'Page not found', nfP: 'This negative didn’t develop.', nfBack: 'Back to home',
  },
};

// ---------- 图标 ----------
const ICONS = `<svg width="0" height="0" style="position:absolute" aria-hidden="true"><defs>
<symbol id="i-star" viewBox="0 0 24 24"><path fill="currentColor" d="M12 3.6l2.55 5.3 5.8.75-4.25 4.02 1.07 5.75L12 16.6l-5.170 2.82 1.07-5.75L3.65 9.65l5.8-.75z"/></symbol>
<symbol id="i-trash" viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" d="M4.5 7h15M10 11v6M14 11v6M6.5 7l.8 11.2A2 2 0 009.3 20h5.4a2 2 0 002-1.8L17.5 7M9.5 7V4.5h5V7"/></symbol>
<symbol id="i-undo" viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" d="M9 14L4.5 9.5 9 5M4.5 9.5H14a5.5 5.5 0 010 11h-3"/></symbol>
<symbol id="i-dl" viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" d="M12 4v11M7.5 10.5L12 15l4.5-4.5M5 19h14"/></symbol>
<symbol id="i-code" viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" d="M8.5 7L3.5 12l5 5M15.5 7l5 5-5 5"/></symbol>
<symbol id="i-play" viewBox="0 0 24 24"><path fill="currentColor" d="M8 5.5v13l10.5-6.5z"/></symbol>
<symbol id="i-pause" viewBox="0 0 24 24"><path fill="currentColor" d="M7 5h3.6v14H7zM13.4 5H17v14h-3.6z"/></symbol>
<symbol id="i-hand" viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" d="M9 11V5.5a1.5 1.5 0 013 0V11m0-1.5a1.5 1.5 0 013 0V12m0-1a1.5 1.5 0 013 0v4a6 6 0 01-6 6h-.6a6 6 0 01-4.7-2.3L4.4 15.6a1.6 1.6 0 012.4-2.1L9 15.5"/></symbol>
<symbol id="i-mute" viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" d="M4 9.5v5h3.5L12 18.5v-13L7.5 9.5zM16 9.5l5 5M21 9.5l-5 5"/></symbol>
<symbol id="i-sound" viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" d="M4 9.5v5h3.5L12 18.5v-13L7.5 9.5zM16 8.5a5 5 0 010 7M18.5 6a8.5 8.5 0 010 12"/></symbol>
<symbol id="i-day" viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" d="M5 5.5h14v14H5zM5 9.5h14M9 3.5v4M15 3.5v4M8.5 14l2.2 2.2 4.8-4.7"/></symbol>
<symbol id="i-multi" viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" d="M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7zM6 7.5l1.2 1.2L9.5 6.4M15 7.5l1.2 1.2 2.3-2.3"/></symbol>
<symbol id="i-lang" viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" d="M3.5 5.5h9M8 3.5v2M10.5 5.5c-.8 3.5-3.3 6.3-6.5 7.8M6 8.5c1.2 2 3 3.6 5 4.5M12.5 20.5l4-9 4 9M14 17h5"/></symbol>
<symbol id="i-gh" viewBox="0 0 24 24"><path fill="currentColor" d="M12 2.5a9.5 9.5 0 00-3 18.5c.5.1.6-.2.6-.5v-1.7c-2.6.6-3.2-1.2-3.2-1.2-.4-1.1-1-1.4-1-1.4-.9-.6 0-.6 0-.6 1 .1 1.5 1 1.5 1 .9 1.5 2.3 1 2.8.8.1-.6.3-1 .6-1.3-2.1-.2-4.3-1-4.3-4.7 0-1 .4-1.9 1-2.6-.1-.2-.4-1.2.1-2.5 0 0 .8-.3 2.6 1a9 9 0 014.8 0c1.8-1.3 2.6-1 2.6-1 .5 1.3.2 2.3.1 2.5.6.7 1 1.6 1 2.6 0 3.7-2.2 4.5-4.3 4.7.3.3.6.9.6 1.8v2.6c0 .3.2.6.7.5A9.5 9.5 0 0012 2.5z"/></symbol>
<symbol id="i-apple" viewBox="0 0 24 24"><path fill="currentColor" d="M16.4 12.6c0-2.4 2-3.5 2-3.6-1.1-1.6-2.8-1.8-3.4-1.8-1.4-.2-2.8.8-3.5.8s-1.8-.8-3-.8C7 7.2 5.6 8.1 4.8 9.5c-1.6 2.8-.4 6.9 1.2 9.1.8 1.1 1.7 2.3 2.8 2.3s1.6-.7 3-.7 1.8.7 3 .7 2-1.1 2.8-2.2c.9-1.3 1.2-2.5 1.3-2.6-.1 0-2.5-.9-2.5-3.5zM14.1 5.6c.6-.8 1.1-1.8.9-2.9-.9 0-2 .6-2.7 1.4-.6.7-1.1 1.7-1 2.8 1 .1 2.1-.5 2.8-1.3z"/></symbol>
</defs></svg>`;
const SBAR = `<div class="sbar" aria-hidden="true"><span>9:41</span><svg viewBox="0 0 46 12"><path fill="currentColor" d="M1 8h2.4v3H1zM5 6h2.4v5H5zM9 3.6h2.4V11H9zM13 1h2.4v10H13z"/><path fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" d="M20.6 4.6a6.2 6.2 0 018.8 0M22.6 6.8a3.4 3.4 0 014.8 0"/><circle cx="25" cy="9.6" r="1.2" fill="currentColor"/><rect x="33.2" y="1.6" width="10.4" height="8.8" rx="2.4" fill="none" stroke="currentColor" stroke-width="1.2" opacity=".6"/><rect x="34.8" y="3.2" width="6.6" height="5.6" rx="1.2" fill="currentColor"/><path d="M45 4.8v2.4" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" opacity=".6"/></svg></div>`;

// ---------- 页面骨架 ----------
function layout(lang, { path: p, title, desc, body, alt, ogType = 'website', json = '' }) {
  const t = L[lang], o = L[t.other];
  const url = ORIGIN + p, altUrl = ORIGIN + alt;
  const zhUrl = lang === 'zh' ? url : altUrl, enUrl = lang === 'en' ? url : altUrl;
  const home = t.base + '/', ch = t.base + '/changelog/';
  const navHref = ['#try', '#features', '#privacy', '#download'].map(h => (p === home ? '' : home) + h).concat(ch);
  return `<!doctype html>
<html lang="${t.html}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>${esc(title)}</title>
<meta name="description" content="${esc(desc)}">
<link rel="canonical" href="${url}">
<link rel="alternate" hreflang="zh-CN" href="${zhUrl}">
<link rel="alternate" hreflang="en" href="${enUrl}">
<link rel="alternate" hreflang="x-default" href="${zhUrl}">
<meta name="theme-color" content="#0F0E0D">
<meta name="color-scheme" content="dark">
<link rel="icon" href="/img/icon.svg" type="image/svg+xml">
<link rel="icon" href="/img/favicon-32.png" sizes="32x32" type="image/png">
<link rel="apple-touch-icon" href="/img/apple-touch-icon.png">
<link rel="manifest" href="/site.webmanifest">
<meta property="og:type" content="${ogType}">
<meta property="og:site_name" content="${t.name}">
<meta property="og:title" content="${esc(title)}">
<meta property="og:description" content="${esc(desc)}">
<meta property="og:url" content="${url}">
<meta property="og:image" content="${ORIGIN}/img/og-${lang}.jpg">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta property="og:locale" content="${t.og}">
<meta property="og:locale:alternate" content="${o.og}">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="${esc(title)}">
<meta name="twitter:description" content="${esc(desc)}">
<meta name="twitter:image" content="${ORIGIN}/img/og-${lang}.jpg">
<link rel="preload" href="/fonts/schibsted_grotesk_700.woff2" as="font" type="font/woff2" crossorigin>
<link rel="stylesheet" href="${CSS}">
${json}</head>
<body>
<a class="skip" href="#main">${t.skip}</a>
${ICONS}
<header class="top"><div class="wrap">
  <a class="brand" href="${home}" aria-label="${t.name}"><img src="/img/icon.svg" alt="" width="32" height="32">${lang === 'zh' ? '理照<span>CleanPhotos</span>' : 'CleanPhotos'}</a>
  <nav aria-label="${lang === 'zh' ? '主导航' : 'Main'}">${t.nav.map((n, i) => `<a href="${navHref[i]}"${i === 4 && p === ch ? ' aria-current="page"' : ''}>${n}</a>`).join('')}</nav>
  <a class="lang" href="${alt}" hreflang="${o.html}" lang="${o.html}" data-lang="${t.other}" aria-label="${t.otherName}">${t.otherLabel}</a>
  <a class="gh" href="${REPO}" target="_blank" rel="noopener" aria-label="${lang === 'zh' ? 'GitHub 源码' : 'Source on GitHub'}"><svg aria-hidden="true"><use href="#i-gh"/></svg><span>GitHub</span></a>
  <a class="get" href="/download/latest" download aria-label="${t.dlBtn}"><svg aria-hidden="true"><use href="#i-dl"/></svg><span>${t.getShort}</span></a>
</div></header>
<main id="main">
${body}
</main>
<footer><div class="wrap">
  <nav aria-label="${lang === 'zh' ? '页脚' : 'Footer'}"><a href="${t.base}/changelog/">${t.foot[0]}</a><a href="${t.base}/privacy/">${t.foot[1]}</a><a href="${REPO}">${t.foot[2]}</a><a href="${REPO}/issues">${t.foot[3]}</a></nav>
  <p>${t.credit}</p>
</div></footer>
<div class="grain" aria-hidden="true"></div>
<script type="application/json" id="t">${JSON.stringify(t.T)}</script>
<script src="${JS}" defer></script>
</body>
</html>
`;
}

// ---------- 首页 ----------
const PRINTS = [['p186', 4, -4], ['hero', 17, 3], ['p219', 30, -2], ['a', 66, 3], ['p164', 79, -3], ['g', 92, 2]];
function homePage(lang) {
  const t = L[lang], v = latest, base = t.base;
  const shot = (name, cls, alt) => `<div class="mini ${cls} develop"><div class="scr">${SBAR}<img src="/shots/${name}.webp" alt="${esc(alt)}" width="540" height="1200" loading="lazy" decoding="async"></div></div>`;
  const feats = t.feats.map((f, i) => `
    <article class="feat">
      <div class="txt reveal">
        <div class="no">${f.no}</div>
        <h3>${f.h}</h3>
        <p>${f.p}</p>
        ${f.chips ? `<ul class="chips"${f.chipsLabel ? ` aria-label="${esc(f.chipsLabel)}"` : ''}>${f.chips.map((c, j) => `<li${j === f.on ? ' class="on"' : ''}>${c}</li>`).join('')}</ul>` : ''}
      </div>
      <div class="shot-col${f.shots.length > 1 ? ' two' : ''}">
        ${f.shots[1] ? shot(f.shots[1], 'back', '') : ''}
        ${shot(f.shots[0], 'front', f.alt)}
        ${f.tag ? `<div class="tag ${i % 2 ? 'b' : 'a'} reveal d2">${f.tag[0]} <b>${f.tag[1]}</b></div>` : ''}
      </div>
    </article>`).join('');
  const faqHtml = t.faq.map(([q, as], i) => `<details class="reveal"${i === 0 ? ' open' : ''}><summary>${q}</summary><div class="a">${as.map(a => `<p>${a.replace('%c', base + '/changelog/').replace('%p', base + '/privacy/')}</p>`).join('')}</div></details>`).join('\n      ');
  const ld = { '@context': 'https://schema.org', '@type': 'SoftwareApplication', name: t.name, alternateName: lang === 'zh' ? 'CleanPhotos' : '理照', operatingSystem: 'Android 8.0+', applicationCategory: 'UtilitiesApplication', description: t.desc, url: ORIGIN + base + '/', downloadUrl: `${ORIGIN}/download/latest`, softwareVersion: v.version, fileSize: mb(v.size), license: 'https://www.apache.org/licenses/LICENSE-2.0', offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' }, image: `${ORIGIN}/img/icon-512.png`, codeRepository: REPO };
  const body = `
<section class="hero" aria-labelledby="h1">
  <div class="light" aria-hidden="true"></div>
  <div class="prints" aria-hidden="true">${PRINTS.map(([f, x, r], i) => `<div class="print" style="left:${x}%;--r:${r}deg;animation-delay:-${i * 0.9}s"><img src="/photos/print-${f}.webp" alt="" width="240" height="240"></div>`).join('')}</div>
  <div class="wrap">
    <div class="hero-copy">
      <div class="kicker">${t.kicker}</div>
      <h1 id="h1">${t.h1}</h1>
      <p class="lede">${t.lede}</p>
      <div class="cta">
        <a class="btn primary" href="/download/latest" download><svg aria-hidden="true"><use href="#i-dl"/></svg>${t.dl}<small>APK · ${mb(v.size)}</small></a>
        <a class="btn ghost" href="${REPO}"><svg aria-hidden="true"><use href="#i-gh"/></svg>${t.src}</a>
      </div>
      <ul class="meta">${t.meta.map(m => `<li>${m}</li>`).join('')}</ul>
    </div>
    <div class="phone-box">
      <div class="phone"><div class="scr">
        <video id="promo" muted playsinline loop preload="none" poster="/media/poster.webp" data-src="/media/promo-576${lang === 'en' ? '.en' : ''}.mp4" width="576" height="1024" aria-label="${t.videoLabel}"></video>
        <button class="vbtn play" id="vplay" type="button" hidden aria-label="${t.play}"><svg aria-hidden="true"><use href="#i-play"/></svg></button>
        <button class="vbtn" id="vpause" type="button" aria-label="${t.T.pause}"></button>
        <button class="vbtn" id="vsound" type="button" aria-label="${t.soundOn}"></button>
        <div class="glare" aria-hidden="true"></div>
      </div></div>
    </div>
  </div>
  <a class="scroll-cue" href="#try" aria-label="${t.nav[0]}"></a>
</section>

<section class="try" id="try" aria-labelledby="try-h">
  <div class="wrap">
    <div>
      <div class="kicker reveal">${t.tryK}</div>
      <h2 id="try-h" class="reveal d1">${t.tryH}</h2>
      <p class="sub reveal d2">${t.trySub}</p>
      <ul class="keys reveal d3">${t.keys.map(([k, s, c]) => `<li${c ? ` class="${c}"` : ''}><kbd>${k}</kbd>${s}</li>`).join('')}</ul>
    </div>
    <div class="demo">
      <div class="device-box" id="deviceBox"><div class="device" id="device"><div class="screen" id="screen" tabindex="0" role="application" aria-label="${t.demoLabel}">
        ${SBAR}
        <div id="pages"></div>
        <div class="glowUp"></div><div class="vig"></div>
        <div class="chrome" id="chrome"><div class="scrim-top"></div><div class="scrim-bottom"></div>
          <div class="segs" id="segs"></div>
          <div class="rv-date"><b>${t.rvDate}</b><span class="mono" id="time"></span></div>
          <div class="rv-meta"><span class="mono" id="file"></span><span class="hint"><span><i class="dot up"></i>${t.upKeep}</span><span><i class="dot down"></i>${t.downTrash}</span></span></div>
        </div>
        <div class="cue up pre" id="cueUp"><span class="ring"><svg aria-hidden="true"><use href="#i-star"/></svg></span><b><span class="pre-txt">${t.cueUpPre}</span><span class="post-txt">${t.cueUpPost}</span></b></div>
        <div class="cue down pre" id="cueDown"><span class="ring"><svg aria-hidden="true"><use href="#i-trash"/></svg></span><b><span class="pre-txt">${t.cueDownPre}</span><span class="post-txt">${t.cueDownPost}</span></b></div>
        <div class="hand" aria-hidden="true"></div>
        <button class="demo-start" id="demoStart" type="button" hidden><span><svg aria-hidden="true"><use href="#i-hand"/></svg>${t.demoStart}</span></button>
        <div class="undo" id="undo" aria-live="polite"><span class="thumb"><img id="undoImg" alt="" width="40" height="40"></span><span class="txt"><span id="undoT"></span><small id="undoS"></small></span><button id="undoBtn" type="button"><svg aria-hidden="true"><use href="#i-undo"/></svg>${t.undo}</button><span class="timer"><i></i></span></div>
        <div class="done" id="done"><div class="kicker">${t.doneK}</div><h3>${t.doneH}</h3>
          <div class="stats"><div><b id="dF" class="f">0</b><span>${t.dF}</span></div><div><b id="dT" class="t">0</b><span>${t.dT}</span></div><div><b id="dK">0</b><span>${t.dK}</span></div></div>
          <button id="again" type="button">${t.again}</button></div>
      </div></div></div>
      <div class="demo-bar"><div class="tally" aria-live="polite"><span class="f"><b id="cF">0</b>${t.tallyF}</span><span class="t"><b id="cT">0</b>${t.tallyT}</span></div><button class="demo-exit" id="demoExit" type="button" hidden>${t.demoExit}</button></div>
    </div>
  </div>
</section>

<section id="features" aria-labelledby="feat-h">
  <div class="wrap">
    <div class="kicker reveal">${t.featK}</div>
    <h2 id="feat-h" class="reveal d1">${t.featH}</h2>
    <p class="sub reveal d2">${t.featSub}</p>
    <div class="feats">${feats}
    </div>
    <div class="extras">${t.extras.map(([ic, h, p], i) => `<div class="extra reveal d${i + 1}"><svg aria-hidden="true"><use href="#${ic}"/></svg><h3>${h}</h3><p>${p}</p></div>`).join('')}</div>
  </div>
</section>

<section class="privacy" id="privacy" aria-labelledby="priv-h">
  <div class="wrap">
    <div class="kicker reveal">${t.privK}</div>
    <h2 id="priv-h" class="reveal d1">${t.privH}</h2>
    <ul class="assure reveal d2">${t.privWords.map(([w, d]) => `<li><b>${w}</b><span>${d}</span></li>`).join('')}</ul>
    <p class="more reveal">${t.privMore} <a href="${base}/privacy/">${t.privLink} →</a></p>
  </div>
</section>

<section class="download" id="download" aria-labelledby="dl-h">
  <div class="wrap">
    <div class="head"><div class="kicker reveal">${t.dlK}</div><h2 id="dl-h" class="reveal d1">${t.dlH}</h2></div>
    <div class="card apk reveal">
      <div class="row"><img src="/img/icon.svg" alt="" width="64" height="64"><div><h3>${t.apkName}</h3><div class="ver">${t.ver(v)}</div></div></div>
      <a class="btn primary" href="/download/latest" download><svg aria-hidden="true"><use href="#i-dl"/></svg>${t.dlBtn}<small>${mb(v.size)}</small></a>
      <div class="alt">${t.req} · <a href="${base}/changelog/">${t.changelogLink}</a></div>
      <div class="alt">${t.gh.replace('%s', `${REPO}/releases/tag/v${v.version}`)}</div>
    </div>
    <div class="card ios reveal d1">
      <div class="row"><img src="/img/icon.svg" alt="" width="64" height="64"><div><h3>${t.iosName}</h3><span class="pill">${t.iosPill}</span></div></div>
      <p>${t.iosP}</p>
      <a class="btn ghost" href="${REPO}/tree/main/ios"><svg aria-hidden="true"><use href="#i-apple"/></svg>${t.iosBtn}</a>
    </div>
    <p class="note">${t.dlNote}</p>
  </div>
</section>

<section class="faq" id="faq" aria-labelledby="faq-h">
  <div class="wrap">
    <div class="kicker reveal">${t.faqK}</div>
    <h2 id="faq-h" class="reveal d1">${t.faqH}</h2>
    <div class="list">
      ${faqHtml}
    </div>
  </div>
</section>

<section class="final" aria-labelledby="final-h">
  <div class="light" aria-hidden="true"></div>
  <div class="wrap">
    <h2 id="final-h" class="reveal">${t.finalH}</h2>
    <div class="cta reveal d1">
      <a class="btn primary" href="/download/latest" download><svg aria-hidden="true"><use href="#i-dl"/></svg>${t.dl}</a>
      <a class="btn ghost" href="${REPO}"><svg aria-hidden="true"><use href="#i-gh"/></svg>GitHub</a>
    </div>
  </div>
</section>`;
  return layout(lang, { path: base + '/', alt: (lang === 'zh' ? '/en' : '') + '/', title: t.title, desc: t.desc, body, json: `<script type="application/ld+json">${JSON.stringify(ld)}</script>\n` });
}

function changelogPage(lang) {
  const t = L[lang], base = t.base;
  const have = new Set(rel.versions.map(v => v.version));
  const items = changelog.map((c, i) => `
  <article class="rel" id="v${c.version}">
    <h2><span class="num">v${c.version}</span>${c.date ? `<time datetime="${c.date}">${c.date}</time>` : ''}${i === 0 ? `<span class="latest">${t.clLatest}</span>` : ''}</h2>
    <div class="md">${md(c[lang], { headings: t.clHead })}</div>
    ${have.has(c.version) ? `<div class="dl"><a href="/download/CleanPhotos-${c.version}.apk" download>${t.clDl}</a> · ${mb(rel.versions.find(v => v.version === c.version).size)}</div>` : `<div class="dl"><a href="${REPO}/releases/tag/v${c.version}">GitHub Release</a></div>`}
  </article>`).join('');
  const body = `<div class="doc"><div class="wrap">
  <div class="kicker">${t.name}</div>
  <h1>${t.clTitle}</h1>
  <p class="lead">${t.clLead.replace('%s', `${REPO}/blob/main/CHANGELOG.md`)}</p>
  ${items}
</div></div>`;
  return layout(lang, { path: base + '/changelog/', alt: (lang === 'zh' ? '/en' : '') + '/changelog/', title: `${t.clTitle} · ${t.name}`, desc: t.clDesc, body });
}

function privacyPage(lang) {
  const t = L[lang], base = t.base;
  const body = `<div class="doc"><div class="wrap">
  <div class="kicker">${t.name}</div>
  <h1>${t.pvTitle}</h1>
  <div class="md">${md(privacy[lang])}
  <h2>${t.pvSite}</h2>
  <p>${t.pvSiteP}</p></div>
</div></div>`;
  return layout(lang, { path: base + '/privacy/', alt: (lang === 'zh' ? '/en' : '') + '/privacy/', title: `${t.pvTitle} · ${t.name}`, desc: t.pvDesc, body });
}

function notFoundPage() {
  const z = L.zh, e = L.en;
  const body = `<div class="doc err"><div class="wrap">
  <div class="kicker" style="justify-content:center">404</div>
  <h1>${z.nfTitle}</h1><p class="lead">${z.nfP} <a href="/">${z.nfBack}</a></p>
  <p class="lead" lang="en">${e.nfTitle}. ${e.nfP} <a href="/en/">${e.nfBack}</a></p>
</div></div>`;
  return layout('zh', { path: '/404', alt: '/en/', title: `404 · ${z.name}`, desc: z.desc, body }).replace(/<link rel="canonical"[^>]*>\n/, '<meta name="robots" content="noindex">\n');
}

for (const lang of ['zh', 'en']) {
  const b = L[lang].base;
  write(`${b}/index.html`.replace(/^\//, ''), homePage(lang));
  write(`${b}/changelog/index.html`.replace(/^\//, ''), changelogPage(lang));
  write(`${b}/privacy/index.html`.replace(/^\//, ''), privacyPage(lang));
}
write('404.html', notFoundPage());

const pairs = [['/', '/en/'], ['/changelog/', '/en/changelog/'], ['/privacy/', '/en/privacy/']];
const today = day(latest.date);
write('sitemap.xml', `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">
${pairs.flatMap(([zh, en]) => [zh, en].map(u => `  <url><loc>${ORIGIN}${u}</loc><lastmod>${today}</lastmod>
    <xhtml:link rel="alternate" hreflang="zh-CN" href="${ORIGIN}${zh}"/>
    <xhtml:link rel="alternate" hreflang="en" href="${ORIGIN}${en}"/>
    <xhtml:link rel="alternate" hreflang="x-default" href="${ORIGIN}${zh}"/>
  </url>`)).join('\n')}
</urlset>
`);
write('robots.txt', `User-agent: *\nAllow: /\nDisallow: /download/\nDisallow: /apk/\n\nSitemap: ${ORIGIN}/sitemap.xml\n`);
write('site.webmanifest', JSON.stringify({ name: '理照 CleanPhotos', short_name: '理照', start_url: '/', display: 'standalone', background_color: '#0F0E0D', theme_color: '#0F0E0D', icons: [{ src: '/img/icon-192.png', sizes: '192x192', type: 'image/png' }, { src: '/img/icon-512.png', sizes: '512x512', type: 'image/png' }] }, null, 2) + '\n');
write('_headers', `/*
  X-Content-Type-Options: nosniff
  Referrer-Policy: strict-origin-when-cross-origin
  Permissions-Policy: camera=(), microphone=(), geolocation=(), interest-cohort=()
  Content-Security-Policy: default-src 'self'; img-src 'self' data:; media-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'none'
/css/*
  Cache-Control: public, max-age=31536000, immutable
/js/*
  Cache-Control: public, max-age=31536000, immutable
/fonts/*
  Cache-Control: public, max-age=31536000, immutable
/media/*
  Cache-Control: public, max-age=2592000
/shots/*
  Cache-Control: public, max-age=2592000
/photos/*
  Cache-Control: public, max-age=2592000
/img/*
  Cache-Control: public, max-age=2592000
/photos/CREDITS.md
  Content-Type: text/plain; charset=utf-8
`);
const files = [];
(function walk(d) { for (const f of fs.readdirSync(d, { withFileTypes: true })) { const p = path.join(d, f.name); f.isDirectory() ? walk(p) : files.push(fs.statSync(p).size); } })(DIST);
console.log(`dist/: ${files.length} files, ${(files.reduce((a, b) => a + b, 0) / 1048576).toFixed(1)} MB · latest v${latest.version}`);
