#!/usr/bin/env python3
"""检查本次测试（15:30）蓝牙进程 Hook 是否触发。"""
import subprocess


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 蓝牙进程 ===")
print(sh("ps -A | grep com.android.bluetooth"))

print("\n=== 2. 蓝牙进程 logcat（15:29 后） ===")
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and "com.android.bluetooth" in line:
        pid = parts[1]
        out = sh(f"logcat -d --pid={pid} -t 400 | grep -iE 'ZVirtualEnv|ble stack|hooked' | tail -25")
        print(f"bt_pid={pid}:")
        print(out if out else "(none)")

print("\n=== 3. 最新 lspd log 中全部 VirtualEnv 蓝牙行 ===")
print(sh("su -c 'grep -a \"com.android.bluetooth.*VirtualEnv\" /data/adb/lspd/log/modules_*.log | tail -10'") or "(none)")

print("\n=== 4. 蓝牙进程 EnvCache 线程 ===")
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and "com.android.bluetooth" in line:
        pid = parts[1]
        print(f"PID {pid}:", sh(f"su -c 'for t in /proc/{pid}/task/*/comm; do cat $t 2>/dev/null; done' | grep -i ZVE") or "(none)")
