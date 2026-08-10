#!/usr/bin/env python3
"""等待 App UI 就绪，精确点击一键采集，确认采集触发。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(6)

# dump 确认按钮
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
m = re.search(r'text="一键采集"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
print("一键采集 found:", bool(m))
if m:
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    sh("logcat -c")
    print(f"tap {x},{y}")
    sh(f"input tap {x} {y}")
    time.sleep(5)
    # 再 dump 看是否进入采集进行中
    sh("uiautomator dump /sdcard/ui2.xml")
    xml2 = sh("cat /sdcard/ui2.xml")
    if "采集进行中" in xml2:
        print("STATUS: 采集进行中 visible")
    else:
        print("STATUS: not collecting")
        for mm in re.finditer(r'text="([^"]{0,30})"', xml2):
            t = mm.group(1)
            if t.strip():
                print("text:", t)

# 看 App 日志
time.sleep(12)
app = ""
for line in sh("ps -A | grep fairyxh.VirtualEnv").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        app = parts[1]
print("\n=== App 采集日志 ===")
if app:
    print(sh(f"logcat -d --pid={app} -t 300 | grep -iE 'Collect|ble|scan' | tail -20") or "(no collect logs)")
