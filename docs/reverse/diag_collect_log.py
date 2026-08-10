#!/usr/bin/env python3
"""检查采集流程完整日志 + 蓝牙进程 Hook 投递。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 控制端 App 进程采集日志 ===")
app_pid = ""
for line in sh("ps -A | grep fairyxh.VirtualEnv").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        app_pid = parts[1]
print("app_pid=", app_pid)
if app_pid:
    print(sh(f"logcat -d --pid={app_pid} | grep -iE 'Collect|ble|Bluetooth|startScan|scan' | tail -30") or "(none)")

print("\n=== 2. 蓝牙进程全部日志（含 Hook） ===")
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        pid = parts[1]
        print(f"\n--- bt_pid={pid} ---")
        print(sh(f"logcat -d --pid={pid} | grep -iE 'ZVirtualEnv|ble stack|startScan|scan result|virtual' | tail -20") or "(no hook logs)")

print("\n=== 3. 全局 ZVirtualEnv 最近 40 行 ===")
print(sh("logcat -d -t 2000 | grep ZVirtualEnv | tail -40"))
