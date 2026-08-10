#!/usr/bin/env python3
"""关闭 USB 弹窗后重新启动 App。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


def dump():
    sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
    return sh("cat /sdcard/ui.xml")


xml = dump()
if "USB 用于" in xml:
    m = re.search(r'text="仅充电"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        x = (int(m.group(1)) + int(m.group(3))) // 2
        y = (int(m.group(2)) + int(m.group(4))) // 2
        sh(f"input tap {x} {y}")
        print("tapped 仅充电")
    else:
        sh("input keyevent 4")
        print("pressed back")
    time.sleep(2)

print("=== 启动 App ===")
sh("am force-stop io.github.fairyxh.VirtualEnv")
time.sleep(1)
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(10)
xml = dump()
print("主页:", "主页" in xml)
m = re.search(r'text="设置"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    sh(f"input tap {x} {y}")
    print(f"tapped 设置 {x},{y}")
    time.sleep(5)
    xml = dump()
    print("设置页:", "环境实时测试" in xml)
else:
    print("no 设置 tab found")
