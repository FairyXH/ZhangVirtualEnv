#!/usr/bin/env python3
"""全量 logcat 搜索所有关键 hook 日志（Python 直接处理）。"""
import subprocess

p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
keys = [
    "framework env hooks installed",
    "hooked LocationManager",
    "GnssStatus candidates",
    "gnss injector started",
    "gnss virtual deliver",
    "hooked SensorManager",
    "candidates not found",
    "hook register failed",
    "onPackageReady",
    "onModuleLoaded",
    "framework env hook",
]
seen = set()
for line in out.splitlines():
    for k in keys:
        if k in line:
            if line.strip() not in seen:
                seen.add(line.strip())
                print(line.strip())
            break
print("---done---")
