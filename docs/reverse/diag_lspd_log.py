#!/usr/bin/env python3
"""检查 logdaemon 文件、蓝牙进程、最新日志。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. lspd log 文件 ===")
print(sh("su -c 'ls -la /data/adb/lspd/log/'"))

print("\n=== 2. 蓝牙进程 ===")
print(sh("ps -A | grep com.android.bluetooth"))

print("\n=== 3. logdaemon 最后 30 行 ===")
print(sh("su -c 'tail -30 /data/adb/lspd/log/modules.log 2>/dev/null || tail -30 /data/adb/lspd/log/modules_*.log'")[:3000])

print("\n=== 4. 蓝牙进程 logcat ZVirtualEnv ===")
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and "com.android.bluetooth" in line and parts[0] != "root":
        pid = parts[1]
        print(f"bt_pid={pid}:")
        print(sh(f"logcat -d --pid={pid} -t 200 | grep -iE 'ZVirtualEnv|ble stack' | tail -10") or "(none)")
