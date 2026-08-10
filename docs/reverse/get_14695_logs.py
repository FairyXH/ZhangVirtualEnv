#!/usr/bin/env python3
"""抓检测器进程 14695 的 hook 安装日志。"""
import subprocess

p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
lines = out.splitlines()
for line in lines:
    parts = line.split()
    if len(parts) < 3:
        continue
    pid = parts[2]
    if pid == "14695" and ("ZVirtualEnv" in line or "LSPosedLogDaemon" in line):
        print(line[:240])
print("---done---")
