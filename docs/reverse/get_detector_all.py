#!/usr/bin/env python3
"""全量打印检测器进程 12889 的 ZVirtualEnv 日志。"""
import subprocess

p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
lines = out.splitlines()
count = 0
for line in lines:
    parts = line.split()
    if len(parts) < 3:
        continue
    pid = parts[2]
    if pid == "12889" and "ZVirtualEnv" in line:
        print(line)
        count += 1
print(f"---total {count}---")
