#!/usr/bin/env python3
"""强制重启 App → 设置页 → 开始 → 完整 dump 徽标与新布局。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. force-stop + start ===")
sh("am force-stop io.github.fairyxh.VirtualEnv")
time.sleep(2)
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(10)

print("=== 2. 设置 tab ===")
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
m = re.search(r'text="设置"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    sh(f"input tap {x} {y}")
    time.sleep(5)

print("=== 3. 找开始按钮 ===")
for _ in range(5):
    sh("uiautomator dump /sdcard/ui.xml")
    xml = sh("cat /sdcard/ui.xml")
    m2 = re.search(r'text="开始"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m2:
        x = (int(m2.group(1)) + int(m2.group(3))) // 2
        y = (int(m2.group(2)) + int(m2.group(4))) // 2
        sh(f"input tap {x} {y}")
        print(f"tap 开始 {x},{y}")
        break
    sh("input swipe 632 2200 632 800 400")
    time.sleep(1)

time.sleep(12)

print("=== 4. dump 完整文本（含徽标） ===")
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
found_badge = False
for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*bounds="(\[[^\"]+\])"', xml):
    t = m.group(1)
    if t.strip():
        print(f"[{m.group(2)}] {t}")
        if "通过" in t or "未通过" in t or "未启用模拟" in t:
            found_badge = True
print("\nbadge found:", found_badge)
print("has new desc:", "结合虚拟配置判定是否符合预期" in xml)
