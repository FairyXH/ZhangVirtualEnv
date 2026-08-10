#!/usr/bin/env python3
"""force-stop 检测器并重新启动，确认新 raw socket 代码生效。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== force-stop ===")
print(sh("adb shell am force-stop io.github.fairyxh.VirEnvDetector"))
time.sleep(2)
print("=== clear logcat ===")
print(sh("adb logcat -c"))
time.sleep(1)
print("=== start ===")
print(sh("adb shell am start -n io.github.fairyxh.VirEnvDetector/.MainActivity"))
time.sleep(4)
print("=== onCreate log ===")
p = subprocess.run(["adb", "logcat", "-d", "-s", "VirEnvDetector:*"], capture_output=True, timeout=60)
out = p.stdout.decode("utf-8", errors="replace")
print(out[:1500])
