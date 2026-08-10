#!/usr/bin/env python3
"""多次点击一键采集直到采集触发。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(8)

for attempt in range(5):
    sh("uiautomator dump /sdcard/ui.xml")
    xml = sh("cat /sdcard/ui.xml")
    m = re.search(r'text="一键采集"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        print(f"attempt {attempt}: 一键采集 not found")
        time.sleep(3)
        continue
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    print(f"attempt {attempt}: tap {x},{y}")
    sh(f"input tap {x} {y}")
    time.sleep(4)
    sh("uiautomator dump /sdcard/ui2.xml")
    xml2 = sh("cat /sdcard/ui2.xml")
    if "采集进行中" in xml2:
        print("STATUS: collecting!")
        break
    print("  not collecting yet")

# 查看 App 日志
time.sleep(10)
app = ""
for line in sh("ps -A | grep fairyxh.VirtualEnv").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        app = parts[1]
print("\n=== App 采集日志 ===")
if app:
    print(sh(f"logcat -d --pid={app} -t 400 | grep -iE 'Collect|ble|scan' | tail -25") or "(no collect logs)")
