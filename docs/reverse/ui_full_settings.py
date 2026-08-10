#!/usr/bin/env python3
"""完整 dump 设置页，确认环境测试卡片状态。"""
import subprocess
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
for m in re.finditer(r'text="([^"]{0,300})"[^>]*bounds="(\[[^\"]+\])"', xml):
    t = m.group(1)
    if t.strip():
        print(f"[{m.group(2)}] {t}")
