#!/usr/bin/env python3
"""把 Android values/ + values-zh/ 的文案同步进 Localizable.xcstrings（en / zh-Hans）。
复数：Android other → key，en one → key_one。已有 iOS 条目保留 iOS 的格式占位符（按顺序替换）。
用法: sync-android-strings.py [--dry]"""
import json, re, sys, xml.etree.ElementTree as ET
from pathlib import Path
root = Path(__file__).resolve().parents[2]
res = root / 'app/src/main/res'
xs = root / 'ios/CleanPhotos/Resources/Localizable.xcstrings'
SKIP = {'language_en', 'language_zh', 'app_name'}
SPEC = re.compile(r'%(\d+\$)?(lld|ld|d|s|@|f|\.\d+f)')

def clean(s):
    s = s.strip()
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"': s = s[1:-1]
    return s.replace("\\'", "'").replace('\\"', '"').replace('\\n', '\n').replace('\\@', '@')

def load(p):
    d = {}
    for e in ET.parse(p).getroot():
        n = e.get('name')
        if e.get('translatable') == 'false': continue
        if e.tag == 'string': d[n] = clean(''.join(e.itertext()))
        elif e.tag == 'plurals':
            for it in e: d[n + ('_one' if it.get('quantity') == 'one' else '')] = clean(''.join(it.itertext()))
    return d

en, zh = load(res / 'values/strings.xml'), load(res / 'values-zh/strings.xml')
d = json.loads(xs.read_text()); S = d['strings']

def ios_val(k, lang):
    v = S.get(k, {}).get('localizations', {}).get(lang, {})
    return v.get('stringUnit', {}).get('value')

def adapt(a, old):
    a = SPEC.sub(lambda m: '%' + (m.group(1) or '') + ('@' if m.group(2) == 's' else m.group(2)), a)
    if old is None: return a
    olds = [m.group(0) for m in SPEC.finditer(old)]
    news = list(SPEC.finditer(a))
    if len(olds) != len(news): return a
    it = iter(olds)
    return SPEC.sub(lambda m: next(it), a)

changed = []
for k in sorted(en):
    if k in SKIP: continue
    base = k[:-4] if k.endswith('_one') else k
    vals = {'en': en[k], 'zh-Hans': zh.get(k) or zh.get(base) or en[k]}
    for lang, a in vals.items():
        old = ios_val(k, lang)
        if old is None and k.endswith('_one'): old = ios_val(base, lang)
        new = adapt(a, old)
        if lang == 'en' and k == 'header_count_unit': new = old if old is not None else ''
        if new != old:
            changed.append((k, lang, old, new))
            S.setdefault(k, {"extractionState": "manual", "localizations": {}})
            S[k].setdefault('localizations', {})[lang] = {"stringUnit": {"state": "translated", "value": new}}
for c in changed: print(c)
print(len(changed), 'changes')
if '--dry' not in sys.argv:
    xs.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n")
