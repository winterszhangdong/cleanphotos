// 离线跑 site-sync-release.sh：临时目录、假 curl / apksigner，不访问 GitHub，不写仓库里的 site/release。
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const scriptPath = path.join(repo, 'scripts/site-sync-release.sh');
const venvBin = path.join(repo, 'site/.venv/bin');

function hash(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

function runSync({ releases, bytes, digest, apk, tag }) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cleanphotos-sync-'));
  const bin = path.join(root, 'bin');
  fs.mkdirSync(path.join(root, 'scripts'), { recursive: true });
  fs.mkdirSync(bin);
  fs.copyFileSync(scriptPath, path.join(root, 'scripts/site-sync-release.sh'));
  const fixture = path.join(root, 'fixture.json');
  fs.writeFileSync(fixture, JSON.stringify(releases));
  const payload = path.join(root, 'payload.bin');
  fs.writeFileSync(payload, bytes);
  const curl = `#!/bin/bash
out=''
url=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    -H|--retry) shift 2 ;;
    http*|https*) url="$1"; shift ;;
    *) shift ;;
  esac
done
if [[ "$url" == *file.apk ]]; then cat '${payload}' > "$out"; else cat '${fixture}'; fi
`;
  fs.writeFileSync(path.join(bin, 'curl'), curl, { mode: 0o755 });
  fs.writeFileSync(path.join(bin, 'apksigner'), `#!/bin/bash
echo "Signer #1 certificate SHA-256 digest: 78a6bd4f432af27ad7573b951f898d7d33bc7d747c2a42bfeeb29304d58f4980"
`, { mode: 0o755 });
  const args = [path.join(root, 'scripts/site-sync-release.sh')];
  if (apk) args.push('--apk', apk, '--tag', tag);
  const env = {
    ...process.env,
    PATH: [bin, fs.existsSync(path.join(venvBin, 'python3')) ? venvBin : '', process.env.PATH].filter(Boolean).join(':'),
    KEEP: '1',
    REPO: 'fixture/cleanphotos',
  };
  delete env.GITHUB_TOKEN;
  const result = spawnSync('bash', args, { encoding: 'utf8', env });
  let json = null;
  const out = path.join(root, 'site/release/releases.json');
  if (fs.existsSync(out)) json = JSON.parse(fs.readFileSync(out, 'utf8'));
  fs.rmSync(root, { recursive: true, force: true });
  return { ...result, json, digest };
}

function release(bytes, digest) {
  const asset = {
    name: 'CleanPhotos-0.0.1.apk',
    browser_download_url: 'https://fixture.invalid/file.apk',
    size: bytes.length,
  };
  if (digest !== undefined) asset.digest = digest;
  return [{ draft: false, prerelease: false, tag_name: 'v0.0.1', published_at: '2026-10-30T00:00:00Z', assets: [asset] }];
}

test('missing digest does not swallow the file size', () => {
  const bytes = Buffer.from('abcd');
  const result = runSync({ releases: release(bytes), bytes });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.json.versions[0].sha256, hash(bytes));
  assert.notEqual(result.json.versions[0].sha256, String(bytes.length));
  assert.equal(result.json.versions[0].size, bytes.length);
});

test('matching digest is kept', () => {
  const bytes = Buffer.from('apk-ok');
  const digest = 'sha256:' + hash(bytes);
  const result = runSync({ releases: release(bytes, digest), bytes });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.json.versions[0].sha256, hash(bytes));
});

test('wrong digest fails closed', () => {
  const bytes = Buffer.from('apk-bad');
  const result = runSync({ releases: release(bytes, 'sha256:' + '0'.repeat(64)), bytes });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /sha256/);
  assert.equal(result.json, null);
});

test('local apk is the latest release without a github digest', () => {
  const bytes = Buffer.from('local-apk');
  const apk = path.join(os.tmpdir(), `cleanphotos-local-${process.pid}.apk`);
  fs.writeFileSync(apk, bytes);
  try {
    const result = runSync({ releases: [], bytes: Buffer.from(''), apk, tag: 'v9.9.9' });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.json.latest, '9.9.9');
    assert.equal(result.json.versions[0].sha256, hash(bytes));
    assert.equal(result.json.versions[0].size, bytes.length);
  } finally {
    fs.rmSync(apk, { force: true });
  }
});
