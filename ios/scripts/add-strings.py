#!/usr/bin/env python3
"""用法: add-strings.py key 'zh' 'en' [key zh en ...] — 新增 / 覆盖 Localizable.xcstrings 里的条目。"""
import json, sys
from pathlib import Path
p = Path(__file__).resolve().parent.parent / 'CleanPhotos/Resources/Localizable.xcstrings'
d = json.loads(p.read_text())
a = sys.argv[1:]
assert len(a) % 3 == 0
for i in range(0, len(a), 3):
    k, zh, en = a[i:i+3]
    d['strings'][k] = {"extractionState": "manual", "localizations": {
        "en": {"stringUnit": {"state": "translated", "value": en}},
        "zh-Hans": {"stringUnit": {"state": "translated", "value": zh}}}}
p.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n")
print("ok", len(a)//3)
