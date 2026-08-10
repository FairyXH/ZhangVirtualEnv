#!/usr/bin/env python3
"""确认 App 采集是否执行 + 蓝牙进程完整日志。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. App 进程最近日志 ===")
app = ""
for line in sh("ps -A | grep fairyxh.VirtualEnv").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        app = parts[1]
print("app_pid=", app)
if app:
    print(sh(f"logcat -d --pid={app} -t 300 | tail -50"))

print("\n=== 2. 蓝牙进程日志（15:00 后） ===")
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and "com.android.bluetooth" in line:
        pid = parts[1]
        out = sh(f"logcat -d --pid={pid} -t 500 | grep -iE 'ZVirtualEnv|scan|RegisterScanner|BluetoothScanBinder|Transitional|GattService' | tail -30")
        print(f"\nbt_pid={pid}:")
        print(out if out else "(none)")
