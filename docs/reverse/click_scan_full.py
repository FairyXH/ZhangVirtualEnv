#!/usr/bin/env python3
"""清空日志→点击一键采集→抓蓝牙进程+App完整日志。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


# 确保前台
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(3)
sh("logcat -c")

print("tapping 一键采集...")
sh("input tap 260 1200")
time.sleep(20)

print("\n=== App 日志 ===")
app = ""
for line in sh("ps -A | grep fairyxh.VirtualEnv").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        app = parts[1]
if app:
    print(sh(f"logcat -d --pid={app} -t 400 | tail -50"))

print("\n=== 蓝牙进程日志（扫描相关） ===")
bt = ""
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and "com.android.bluetooth" in line:
        bt = parts[1]
if bt:
    print(sh(f"logcat -d --pid={bt} -t 600 | grep -iE 'ZVirtualEnv|scan|GattService|ScanController|registerScanner|startScan' | tail -50"))
