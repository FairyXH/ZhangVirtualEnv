#!/usr/bin/env python3
"""读取检测器 logcat（直接抓，不用 tail 管道）。"""
import subprocess

p = subprocess.run(
    ["adb", "logcat", "-d", "-s", "VirEnvDetector:*"],
    capture_output=True, timeout=90
)
out = p.stdout.decode("utf-8", errors="replace")
print(out[-8000:] if out else "(empty)")
