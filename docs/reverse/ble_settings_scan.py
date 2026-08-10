#!/usr/bin/env python3
"""BLE 已启用状态下触发系统设置页扫描，验证虚拟设备投递。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 确认 BLE 已启用 ===")
print(sh("curl -s http://127.0.0.1:18790/api/env/status | head -c 300"))

print("\n=== 2. 打开蓝牙设置页（触发扫描） ===")
sh("svc bluetooth enable 2>/dev/null || true")
time.sleep(3)
sh("am start -a android.settings.BLUETOOTH_SETTINGS 2>/dev/null || true")
time.sleep(10)

print("\n=== 3. logdaemon: startScan invoked + cache devices ===")
print(sh("su -c 'grep -a \"ble stack startScan\" /data/adb/lspd/log/modules_*.log | tail -12'") or "(none)")

print("\n=== 4. 蓝牙进程 logcat（virtual 投递） ===")
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and "com.android.bluetooth" in line and parts[0] != "root":
        pid = parts[1]
        out = sh(f"logcat -d --pid={pid} -t 400 | grep -iE 'ZVirtualEnv|virtual|ble stack' | tail -15")
        print(f"bt_pid={pid}:")
        print(out if out else "(none)")
