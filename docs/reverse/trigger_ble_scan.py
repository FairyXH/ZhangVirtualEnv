#!/usr/bin/env python3
"""触发 BLE 扫描验证栈内 Hook：清空日志 → 打开蓝牙设置 → 观察。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 清空 logcat ===")
sh("logcat -c")

print("=== 2. 确保蓝牙开启 ===")
sh("svc bluetooth enable 2>/dev/null || true")
time.sleep(3)

print("=== 3. 打开蓝牙设置页 ===")
sh("am start -a android.settings.BLUETOOTH_SETTINGS 2>/dev/null || true")
time.sleep(8)

print("=== 4. 蓝牙进程 Hook 日志 ===")
pid = ""
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        pid = parts[1]
print("bt_pid=", pid)
if pid:
    out = sh(f"logcat -d --pid={pid} | grep -iE 'ZVirtualEnv|ble stack|startScan|scan' | tail -40")
    print(out if out else "(no hook/scan logs)")

print("\n=== 5. 全局 ZVirtualEnv 最近 ===")
print(sh("logcat -d | grep ZVirtualEnv | tail -20"))
