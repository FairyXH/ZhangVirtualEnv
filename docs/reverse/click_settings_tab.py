#!/usr/bin/env python3
"""点击设置 tab，验证设置页与 BLE 测试按钮。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 点击设置 tab ===")
sh("input tap 1064 2661")
time.sleep(4)
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
print("has BLE test:", "开始扫描 8 秒" in xml)
print("has 高德地图 Key:", "高德地图 Key" in xml)
for m in re.finditer(r'text="([^"]{0,40})"[^>]*bounds="(\[[^\"]+\])"', xml):
    print(" ", m.group(1), m.group(2))

print("\n=== 2. 滚动到 BLE 测试 ===")
sh("input swipe 632 2200 632 600 500")
time.sleep(3)
sh("uiautomator dump /sdcard/ui2.xml")
xml2 = sh("cat /sdcard/ui2.xml")
m2 = re.search(r'text="开始扫描 8 秒"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml2)
print("BLE button found:", bool(m2))
if m2:
    x = (int(m2.group(1)) + int(m2.group(3))) // 2
    y = (int(m2.group(2)) + int(m2.group(4))) // 2
    print(f"tap {x},{y}")
    sh(f"input tap {x} {y}")
    time.sleep(12)
    print("\n=== 3. 点击后 UI ===")
    sh("uiautomator dump /sdcard/ui3.xml")
    xml3 = sh("cat /sdcard/ui3.xml")
    for mm in re.finditer(r'text="([^"]{0,60})"', xml3):
        t = mm.group(1)
        if t.strip():
            print(" ", t)
