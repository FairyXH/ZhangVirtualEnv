#!/usr/bin/env python3
"""抓 14842 进程完整安装日志。"""
import subprocess

p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
lines = out.splitlines()
for line in lines:
    parts = line.split()
    if len(parts) < 3:
        continue
    pid = parts[2]
    if pid == "14842" and ("ZVirtualEnv" in line or "LSPosedLogDaemon" in line):
        print(line[:220])
print("---done---")
