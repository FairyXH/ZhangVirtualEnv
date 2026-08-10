#!/usr/bin/env python3
"""检查 App 当前界面，精确点击一键采集，验证 App 端 BLE 扫描发起。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 前台与 App 状态 ===")
print(sh("dumpsys activity top | grep -E 'ACTIVITY' | head -5"))

print("=== 2. dump UI ===")
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
print("xml len:", len(xml))
# 打印所有 text 节点
for m in re.finditer(r'text="([^"]{0,40})"', xml):
    t = m.group(1)
    if t.strip():
        print("text:", t)
