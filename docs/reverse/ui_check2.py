#!/usr/bin/env python3
"""手动分步：确认 App 界面 + 设置页 + BLE 按钮。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 当前前台 ===")
print(sh("dumpsys activity top | grep ACTIVITY | head -3"))

print("\n=== 2. 启动 App ===")
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(8)
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
print("has 设置 tab:", "text=\"设置\"" in xml)
print("has 一键采集:", "一键采集" in xml)
for m in re.finditer(r'text="([^"]{0,30})"[^>]*bounds="(\[[^\"]+\])"', xml):
    print(" ", m.group(1), m.group(2))
