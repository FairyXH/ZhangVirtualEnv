#!/usr/bin/env python3
"""全量搜索 GnssStatus 相关行（含枚举）。"""
import subprocess

p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
lines = out.splitlines()
for line in lines:
    if "GnssStatus" in line:
        print(line[:250])
print("---done---")
