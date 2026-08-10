#!/usr/bin/env python3
"""定位持有 ScanController（BluetoothScanManager 线程）的蓝牙进程实例。"""
import subprocess


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 各蓝牙进程线程名（找 BluetoothScanManager / GattService） ===")
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) < 2 or "com.android.bluetooth" not in line:
        continue
    pid = parts[1]
    print(f"\n--- PID {pid} ({parts[0]}) ---")
    out = sh(f"su -c 'for t in /proc/{pid}/task/*/comm; do cat $t 2>/dev/null; done' | sort | uniq -c | sort -rn | head -40")
    print(out)

print("\n=== 2. BluetoothManagerService / GattService binder 归属 ===")
print(sh("dumpsys activity services | grep -iE 'bluetooth|gatt' | head -10"))
print(sh("dumpsys bluetooth_manager | head -5"))
