#!/usr/bin/env python3
"""为 make run-ios 选一台已配对的 iPhone。成功时打印 udid<TAB>name。"""
import json
import sys


def fields(dev):
    props = dev.get("properties") or {}
    hw = props.get("hardware") or dev.get("hardwareProperties") or {}
    conn = props.get("connection") or dev.get("connectionProperties") or {}
    state = props.get("state") or dev.get("deviceProperties") or {}
    return {
        "name": state.get("name") or props.get("name") or "iPhone",
        "udid": hw.get("udid") or props.get("udid") or "",
        "reality": hw.get("reality") or props.get("reality") or "",
        "connected": conn.get("state") == "connected",
    }


def main():
    data = json.load(open(sys.argv[1]))
    physical = [fields(d) for d in data["result"]["devices"]]
    physical = [d for d in physical if d["reality"] == "physical" and d["udid"]]
    online = [d for d in physical if d["connected"]]
    if len(online) == 1:
        chosen = online[0]
    elif len(online) > 1:
        lines = "\n".join(f"  {d['name']}  {d['udid']}" for d in online)
        sys.exit("连着多台 iPhone，用 IOS_DEVICE=UDID 指定一台：\n" + lines)
    elif len(physical) == 1:
        chosen = physical[0]
    elif len(physical) > 1:
        lines = "\n".join(f"  {d['name']}  {d['udid']}" for d in physical)
        sys.exit("没有已连接的 iPhone。用 IOS_DEVICE=UDID 指定一台：\n" + lines)
    else:
        sys.exit("没有已配对的 iPhone。解锁手机，用数据线连接或连上同一 Wi-Fi，并在手机上点信任")
    print(chosen["udid"] + "\t" + chosen["name"])


if __name__ == "__main__":
    main()
