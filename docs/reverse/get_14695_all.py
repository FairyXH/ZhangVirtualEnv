#!/usr/bin/env python3
"""抓 14695 进程所有 ZVirtualEnv / LSPosedLogDaemon 日志。"""
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
    if pid == "14695" and ("ZVirtualEnv" in line or "LSPosedLogDaemon" in line):
        rows.append(line)
# 打印前 25 行（安装日志应该在最前）
for line in rows[:25]:
    print(line[:240])
print(f"---total {len(rows)}---")
