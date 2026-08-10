#!/usr/bin/env python3
"""点击一键采集并实时捕获 ZVirtualEnv 日志（重点 BLE Hook）。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


proc = subprocess.Popen(
    ["adb", "logcat", "-v", "time", "-s", "ZVirtualEnv:*"],
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    errors="replace",
)
time.sleep(1)
# 点击一键采集
sh("input tap 260 1200")
time.sleep(15)
proc.terminate()
lines = []
try:
    while True:
        line = proc.stdout.readline()
        if not line:
            break
        lines.append(line.strip())
except Exception:
    pass
print("captured:", len(lines))
print("\n".join(lines[-50:]) if lines else "(none)")
