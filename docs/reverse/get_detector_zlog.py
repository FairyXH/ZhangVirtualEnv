#!/usr/bin/env python3
"""抓取检测器进程（12889）的完整 ZVirtualEnv 日志。"""
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
        print(line)
print("---done---")
