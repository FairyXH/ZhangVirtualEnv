#!/usr/bin/env python3
"""尝试系统 LE 扫描入口 + 验证虚拟 BLE 投递。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 确认 BLE 已启用 ===")
print(sh("curl -s http://127.0.0.1:18790/api/env/status | head -c 250"))

print("\n=== 2. 尝试各 LE 扫描入口 ===")
for intent in [
    "android.settings.BLUETOOTH_SCAN_SETTINGS",
    "android.bluetooth.adapter.action.REQUEST_ENABLE_LE",
    "android.settings.BLUETOOTH_SETTINGS",
]:
    out = sh(f"am start -a {intent} 2>&1 || true")
    print(f"{intent}: {out[:120]}")
    time.sleep(6)

print("\n=== 3. logdaemon 最新 startScan ===")
print(sh("su -c 'grep -a \"ble stack startScan\" /data/adb/lspd/log/modules_*.log | tail -8'") or "(none)")

print("\n=== 4. 蓝牙进程 shim 层扫描 ===")
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and "com.android.bluetooth" in line and parts[0] != "root":
        pid = parts[1]
        out = sh(f"logcat -d --pid={pid} -t 300 | grep -iE 'RegisterScanner|reportBleScan|virtual|ZVirtualEnv' | tail -10")
        print(f"bt_pid={pid}:")
        print(out if out else "(none)")
