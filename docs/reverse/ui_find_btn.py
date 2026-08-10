#!/usr/bin/env python3
"""重新 dump UI 找采集按钮，点击后验证新日志出现。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 当前前台 Activity ===")
print(sh("dumpsys activity top | grep -E 'ACTIVITY' | head -3"))

print("\n=== 2. dump UI ===")
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
print("xml len:", len(xml))
for m in re.finditer(r'text="([^"]*采集[^"]*)"[^>]*bounds="(\[[^"]+\])"', xml):
    print("btn:", m.group(1), m.group(2))
