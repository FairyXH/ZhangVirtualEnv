#!/usr/bin/env python3
"""抓检测器进程完整 ZVirtualEnv 日志，找 GnssStatus candidates。"""
import subprocess

p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
lines = out.splitlines()
for line in lines:
    parts = line.split()
    if len(parts) < 3:
        continue
    pid = parts[2]
    if pid == "12889" and "ZVirtualEnv" in line:
        if any(k in line for k in [
            "framework env hooks installed",
            "GnssStatus",
            "registerGnssStatusCallback",
            "unregisterGnssStatusCallback",
            "getGnssStatus",
            "hooked LocationManager",
            "candidates not found",
            "hooked SensorManager",
        ]):
            print(line)
print("---done---")
