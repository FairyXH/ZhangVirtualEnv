#!/usr/bin/env python3
"""完整检查蓝牙进程实例与 Binder 服务归属。"""
import subprocess


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 全部 bluetooth 进程 ===")
print(sh("ps -A -o PID,USER,NAME | grep -iE 'bluetooth'"))

print("\n=== 2. AdapterService / GattService 归属 ===")
print(sh("dumpsys activity services | grep -B2 -A6 -iE 'com.android.bluetooth/.btservice.AdapterService|BluetoothGatt|bluetooth_le' | head -40"))

print("\n=== 3. binder 服务持有者（IBluetoothScan 等） ===")
print(sh("dumpsys service bluetooth_manager | grep -iE 'pid|app=' | head -5"))

print("\n=== 4. lspd 注入记录（最近） ===")
print(sh("su -c 'grep -a \"com.android.bluetooth\" /data/adb/lspd/log/modules_*.log | grep -i virtualenv | tail -4'"))

print("\n=== 5. 两个实例 ZVE 线程与 Hook 日志 ===")
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and "com.android.bluetooth" in line:
        pid = parts[1]
        print(f"\n--- {parts[0]} PID {pid} ---")
        print("ZVE thread:", sh(f"su -c 'for t in /proc/{pid}/task/*/comm; do cat $t 2>/dev/null; done' | grep -i ZVE") or "(none)")
        print("hook logs:", sh(f"logcat -d --pid={pid} | grep -iE 'ZVirtualEnv|ble stack' | tail -5") or "(none)")
