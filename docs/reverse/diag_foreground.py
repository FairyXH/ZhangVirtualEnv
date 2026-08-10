#!/usr/bin/env python3
"""查看当前前台与 UI 全文本。"""
import subprocess
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 前台 ===")
print(sh("dumpsys activity top | grep ACTIVITY | head -3"))
print("\n=== UI 文本 ===")
sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
xml = sh("cat /sdcard/ui.xml")
for m in re.finditer(r'<node[^>]*text="([^"]{0,40})"', xml):
    if m.group(1).strip():
        print(" ", m.group(1))
print("\n=== App 状态 ===")
print(sh("ps -A | grep fairyxh.VirtualEnv") or "(not running)")
print(sh("dumpsys window | grep mCurrentFocus"))
