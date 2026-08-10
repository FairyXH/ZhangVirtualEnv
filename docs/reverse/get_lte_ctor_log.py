#!/usr/bin/env python3
"""抓取 CellIdentityLte 构造器枚举日志。"""
import subprocess

p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
for line in out.splitlines():
    if "CellIdentityLte ctor" in line or "ctor enum" in line or "CellIdentityLte(" in line:
        print(line)
print("---done---")
