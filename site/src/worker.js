// 理照官网 Worker：下载、版本接口、首页语言跳转；其余请求交给静态资源（dist/）。
// 版本数据在构建时写进 dist/api/releases.json（来自 scripts/site-sync-release.sh），APK 在 dist/apk/。
// 下载全部由 Cloudflare 直接提供，不在请求时访问 GitHub；只有官网没存的旧版本才跳转到 GitHub Release。

const VERSIONED = /^\/download\/CleanPhotos-(\d+\.\d+\.\d+)\.apk$/;
const LATEST = new Set(['/download/latest', '/download/latest/', '/download/CleanPhotos-latest.apk', '/download']);

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    if (request.method !== 'GET' && request.method !== 'HEAD') return new Response('Method Not Allowed', { status: 405, headers: { Allow: 'GET, HEAD' } });

    if (path === '/') return home(request, env, url);
    if (path.startsWith('/apk/')) return new Response('Not Found', { status: 404 });   // 只能经 /download/ 下载
    if (path === '/api/latest.json' || path === '/api/releases.json') return api(request, env, url, path);
    if (LATEST.has(path) || VERSIONED.test(path)) return download(request, env, url, path);
    return env.ASSETS.fetch(request);
  },
};

async function releases(env, url) {
  const r = await env.ASSETS.fetch(new URL('/api/releases.json', url));
  if (!r.ok) throw new Error('releases.json missing');
  return r.json();
}

async function api(request, env, url, path) {
  const data = await releases(env, url);
  const body = path === '/api/latest.json' ? data.versions.find(v => v.version === data.latest) : data;
  return new Response(JSON.stringify(body, null, 2) + '\n', {
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'public, max-age=300', 'access-control-allow-origin': '*' },
  });
}

async function download(request, env, url, path) {
  const data = await releases(env, url);
  const m = path.match(VERSIONED);
  const version = m ? m[1] : data.latest;
  const rel = data.versions.find(v => v.version === version);
  if (!rel) {
    // 官网只存最近几版；更早的版本跳到 GitHub Release
    if (m) return Response.redirect(`${data.repo}/releases/download/v${version}/CleanPhotos-${version}.apk`, 302);
    return new Response('Not Found', { status: 404 });
  }
  const asset = await env.ASSETS.fetch(new Request(new URL(`/apk/${rel.file}`, url), { method: request.method, headers: request.headers }));
  if (!asset.ok && asset.status !== 206 && asset.status !== 304) return new Response('Not Found', { status: 404 });
  const h = new Headers(asset.headers);
  h.set('content-type', 'application/vnd.android.package-archive');
  h.set('content-disposition', `attachment; filename="${rel.file}"`);
  h.set('x-content-sha256', rel.sha256);
  h.set('x-app-version', rel.version);
  // 「最新版」地址随发版变化，不让浏览器和 CDN 长期缓存；带版本号的地址内容不变
  h.set('cache-control', m ? 'public, max-age=31536000, immutable' : 'no-cache');
  h.set('x-content-type-options', 'nosniff');
  return new Response(asset.body, { status: asset.status, headers: h });
}

// 首页：没有手动选过语言时按 Accept-Language，首选不是中文就去 /en/
function home(request, env, url) {
  const cookie = request.headers.get('cookie') || '';
  const chosen = (cookie.match(/(?:^|;\s*)lang=(zh|en)/) || [])[1];
  const lang = chosen || preferred(request.headers.get('accept-language'));
  if (lang === 'en') {
    return new Response(null, { status: 302, headers: { location: '/en/' + url.search, vary: 'accept-language, cookie', 'cache-control': 'private, no-store' } });
  }
  return env.ASSETS.fetch(request).then(r => {
    const res = new Response(r.body, r);
    res.headers.append('vary', 'accept-language, cookie');
    return res;
  });
}

export function preferred(header) {
  if (!header) return 'zh';   // 爬虫和没有语言偏好的请求给中文版（默认语言）
  const langs = header.split(',').map(part => {
    const [tag, ...params] = part.trim().toLowerCase().split(';');
    const q = params.map(p => p.trim()).find(p => p.startsWith('q='));
    return { tag, q: q ? parseFloat(q.slice(2)) || 0 : 1 };
  }).filter(l => l.tag && l.q > 0).sort((a, b) => b.q - a.q);
  for (const { tag } of langs) {
    if (tag.startsWith('zh')) return 'zh';
    if (tag.startsWith('en')) return 'en';
  }
  return langs.length ? 'en' : 'zh';   // 其他语言的访客看英文
}
