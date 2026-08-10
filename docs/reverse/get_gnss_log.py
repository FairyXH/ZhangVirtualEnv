#!/usr/bin/env python3
"""抓取 gnss injector 与 framework env hooks 安装日志。"""
import subprocess

p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
for line in out.splitlines():
    if any(k in line for k in [
        "gnss injector", "gnss virtual", "GnssStatus", "registerGnssStatusCallback",
        "framework env hooks installed", "unregisterGnssStatusCallback",
        "sensor injector started", "CellIdentityLte ctor",
    ]):
        print(line)
print("---done---")
