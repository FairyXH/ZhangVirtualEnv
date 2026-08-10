#!/usr/bin/env python3
"""确认 App 采集是否执行 + 蓝牙诊断日志。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. App 进程 ===")
app_pid = ""
for line in sh("ps -A | grep fairyxh.VirtualEnv").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        app_pid = parts[1]
print("app_pid=", app_pid)
if app_pid:
    print("=== App 最近 60 行 ===")
    print(sh(f"logcat -d --pid={app_pid} -t 200 | tail -60"))

print("\n=== 2. 蓝牙进程诊断日志 ===")
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and "com.android.bluetooth" in line:
        pid = parts[1]
        out = sh(f"logcat -d --pid={pid} | grep -iE 'ZVirtualEnv|ble stack|BluetoothScan|ScanController|startScan' | tail -15")
        print(f"\nbt_pid={pid}:")
        print(out if out else "(none)")

print("\n=== 3. 当前前台 ===")
print(sh("dumpsys activity top | grep -E 'ACTIVITY' | head -3"))
