#!/usr/bin/env python3
"""触发采集后检查蓝牙进程扫描链路日志，确认 startScan 是否到达。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 触发 App 采集 ===")
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(4)
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
m = re.search(r'text="一键采集"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    sh("logcat -c")
    print(f"tap {x},{y}")
    sh(f"input tap {x} {y}")
    time.sleep(18)

print("\n=== 2. 蓝牙进程全日志（扫描链路） ===")
bt = ""
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and "com.android.bluetooth" in line:
        bt = parts[1]
print("bt_pid=", bt)
if bt:
    out = sh(f"logcat -d --pid={bt} -t 500 | grep -iE 'scan|GattService|ScanController|Transitional|registerScanner|startScan' | tail -40")
    print(out if out else "(no scan logs)")

print("\n=== 3. App 进程采集日志 ===")
app = ""
for line in sh("ps -A | grep fairyxh.VirtualEnv").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        app = parts[1]
print("app_pid=", app)
if app:
    print(sh(f"logcat -d --pid={app} -t 300 | grep -iE 'Collect|ble|scan' | tail -20") or "(none)")

print("\n=== 4. 全局 ZVirtualEnv ===")
print(sh("logcat -d -t 500 | grep ZVirtualEnv | tail -10"))
