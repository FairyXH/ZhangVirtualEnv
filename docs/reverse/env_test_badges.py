#!/usr/bin/env python3
"""确认 UI 徽标显示。"""
import subprocess
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
print("has 通过:", "通过" in xml)
print("has 未通过:", "未通过" in xml)
print("has 未启用模拟:", "未启用模拟" in xml)
for m in re.finditer(r'<node[^>]*text="([^"]*(?:通过|未通过|未启用模拟|位置|基站|蓝牙|WiFi)[^"]*)"[^>]*bounds="(\[[^\"]+\])"', xml):
    print(f"[{m.group(2)}] {m.group(1)}")
