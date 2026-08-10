#!/usr/bin/env python3
"""验证 BLE 虚拟化端到端：设置虚拟设备 → 触发扫描 → 观察投递。"""
import subprocess
import time
import json


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


def curl(method: str, path: str, body: str = "") -> str:
    if body:
        cmd = f'curl -s -X {method} -H "Content-Type: application/json" -d \'{body}\' http://127.0.0.1:18790{path}'
    else:
        cmd = f'curl -s -X {method} http://127.0.0.1:18790{path}'
    return sh(cmd)


print("=== 1. forward + API ===")
subprocess.run(["adb", "forward", "tcp:18790", "tcp:18790"], capture_output=True, timeout=30)
print(curl("GET", "/api/status")[:200])

print("\n=== 2. 设置虚拟 BLE ===")
ble_body = json.dumps({
    "devices": [
        {"name": "ZVE-Virtual-Band", "address": "AA:BB:CC:DD:EE:01", "rssi": -55},
        {"name": "ZVE-Virtual-Beacon", "address": "AA:BB:CC:DD:EE:02", "rssi": -70},
    ],
    "bonded": []
}, ensure_ascii=False)
print(curl("POST", "/api/bluetooth/set", ble_body)[:300])

print("\n=== 3. 清空 logcat + 触发扫描 ===")
sh("logcat -c")
sh("svc bluetooth enable 2>/dev/null || true")
time.sleep(3)
sh("am start -a android.settings.BLUETOOTH_SETTINGS 2>/dev/null || true")
time.sleep(8)

print("\n=== 4. 蓝牙进程 Hook 投递日志 ===")
pid = ""
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        pid = parts[1]
print("bt_pid=", pid)
if pid:
    out = sh(f"logcat -d --pid={pid} | grep -iE 'ZVirtualEnv|ble stack|virtual' | tail -30")
    print(out if out else "(no hook logs)")

print("\n=== 5. 设置页是否显示虚拟设备（dumpsys bluetooth nearby） ===")
print(sh("dumpsys bluetooth_manager | grep -iE 'ZVE-Virtual|AA:BB:CC:DD:EE' | head -5") or "(not in dumpsys)")
