#!/usr/bin/env python3
"""检查当前前台与 UI 文本。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 前台 ===")
print(sh("dumpsys activity top | grep ACTIVITY | head -3"))

print("\n=== 当前 UI 文本 ===")
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
for m in re.finditer(r'text="([^"]{0,40})"', xml):
    t = m.group(1)
    if t.strip():
        print(" ", t)
