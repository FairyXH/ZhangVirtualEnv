#!/usr/bin/env python3
"""检查蓝牙进程 Hook 安装细节。"""
import subprocess


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


pid = ""
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        pid = parts[1]
print("bt_pid=", pid)

print("\n=== 蓝牙进程 logcat ZVirtualEnv ===")
print(sh(f"logcat -d --pid={pid} -t 800 | grep -iE 'ZVirtualEnv|TransitionalScanHelper|class not found' | tail -40"))

print("\n=== 全局 ZVirtualEnv 最近 ===")
print(sh("logcat -d -t 500 | grep -iE 'ZVirtualEnv' | tail -30"))
