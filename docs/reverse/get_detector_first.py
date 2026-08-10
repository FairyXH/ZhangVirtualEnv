#!/usr/bin/env python3
"""抓检测器进程 12889 最早的 30 条 ZVirtualEnv 日志。"""
import subprocess

p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
lines = out.splitlines()
rows = []
for line in lines:
    parts = line.split()
    if len(parts) < 3:
        continue
    pid = parts[2]
    if pid == "12889" and "ZVirtualEnv" in line:
        rows.append(line)
for line in rows[:30]:
    print(line)
print(f"---total {len(rows)}---")
