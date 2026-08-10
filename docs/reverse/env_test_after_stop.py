#!/usr/bin/env python3
"""确认结束后按钮状态与六栏数据保留。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
for label in ["开始", "结束"]:
    m = re.search(rf'text="{label}"[^>]*enabled="(\w+)"[^>]*bounds="(\[[^\"]+\])"', xml)
    if not m:
        m = re.search(rf'text="{label}"[^>]*bounds="(\[[^\"]+\])"', xml)
        print(label, "bounds:", m.group(1) if m else None, "(enabled attr not in dump)")
    else:
        print(label, "enabled:", m.group(1), m.group(2))

print("\n六栏数据（结束后保留）:")
for label in ["位置", "基站", "蓝牙", "WiFi", "传感器", "GNSS"]:
    m = re.search(rf'text="{label}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        y = (int(m.group(2)) + int(m.group(4))) // 2
        vals = []
        for mm in re.finditer(r'text="([^"]{4,300})"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
            t = mm.group(1)
            yy = (int(mm.group(3)) + int(mm.group(5))) // 2
            if 0 < yy - y < 200 and t not in ["开始", "结束", "主页", "位置", "路线", "环境", "设置"]:
                vals.append(t)
        print(f"  {label}: {' | '.join(vals[:3]) if vals else '(空)'}")
    else:
        print(f"  {label}: (不可见)")
