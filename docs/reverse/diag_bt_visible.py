#!/usr/bin/env python3
"""验证蓝牙进程日志可见性 + 触发扫描。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


pid = ""
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        pid = parts[1]
print("bt_pid=", pid)

print("\n=== 清空 logcat ===")
sh("logcat -c")

print("=== 触发 BLE 扫描（设置页） ===")
sh("am start -a android.settings.BLUETOOTH_SETTINGS 2>/dev/null || true")
time.sleep(6)

print("\n=== 蓝牙进程 logcat（grep 7449 方式） ===")
out = sh(f"logcat -d | grep ' {pid} ' | grep -iE 'ZVirtualEnv|ScanController|Transitional|startScan|scan' | tail -30")
print(out if out else "(none)")

print("\n=== 蓝牙进程 logcat 全部（前 15 行） ===")
print(sh(f"logcat -d | grep ' {pid} ' | tail -15"))
