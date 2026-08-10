#!/usr/bin/env python3
"""检查蓝牙进程 logcat 完整 ZVirtualEnv 输出 + 触发真实 LE 扫描。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


pid = ""
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2:
        pid = parts[1]
print("bt_pid=", pid)

print("\n=== 蓝牙进程 logcat ZVirtualEnv（最近 500 行） ===")
print(sh(f"logcat -d --pid={pid} -t 500 | grep -iE 'ZVirtualEnv|Hook' | tail -40"))

print("\n=== 全局 logcat ZVirtualEnv（最近） ===")
print(sh("logcat -d -t 800 | grep -iE 'ZVirtualEnv|TransitionalScanHelper' | tail -30"))
