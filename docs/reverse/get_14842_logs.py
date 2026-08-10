#!/usr/bin/env python3
"""抓新检测器进程 14842 的 gnss/sensor 注入日志。"""
import subprocess

p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
lines = out.splitlines()
for line in lines:
    parts = line.split()
    if len(parts) < 3:
        continue
    pid = parts[2]
    if pid == "14842" and "ZVirtualEnv" in line:
        if any(k in line for k in [
            "gnss injector", "GnssStatus", "registerGnssStatusCallback",
            "sensor inject", "StepHook", "EnvCache", "framework env",
        ]):
            print(line[:200])
print("---done---")
