#!/usr/bin/env python3
"""查看检测器 UI 当前状态与原始 logcat。"""
import subprocess


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== detector process ===")
print(sh("adb shell ps -A 2>&1 | grep VirEnvDetector"))
print("=== UI dump ===")
print(sh("adb shell uiautomator dump /sdcard/d.xml 2>&1"))
xml = sh("adb shell cat /sdcard/d.xml 2>&1")
import re
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    t, x1, y1, x2, y2 = m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5))
    print(f"  [{x1},{y1}][{x2},{y2}] {t}")
print("=== logcat VirEnvDetector (raw, all) ===")
p = subprocess.run(["adb", "logcat", "-d", "-s", "VirEnvDetector:*"], capture_output=True, timeout=60)
print(p.stdout.decode("utf-8", errors="replace")[-3000:] or "(empty)")
