#!/usr/bin/env python3
"""验证虚拟 BLE：设置数据 → 触发扫描 → 观察栈内 Hook 投递。"""
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


print("=== 1. adb forward ===")
subprocess.run(["adb", "forward", "tcp:18790", "tcp:18790"], capture_output=True, timeout=30)
print("forward ok")

print("\n=== 2. API 存活 ===")
print(curl("GET", "/api/status")[:300])

print("\n=== 3. 设置虚拟 BLE 设备 ===")
ble_body = json.dumps({
    "devices": [
        {"name": "ZVE-Virtual-Band", "address": "AA:BB:CC:DD:EE:01", "rssi": -55},
        {"name": "ZVE-Virtual-Beacon", "address": "AA:BB:CC:DD:EE:02", "rssi": -70},
    ],
    "bonded": []
}, ensure_ascii=False)
print(curl("POST", "/api/bluetooth/set", ble_body)[:400])

print("\n=== 4. 触发扫描（系统蓝牙设置页启动一次 LE 扫描） ===")
sh("am start -a android.bluetooth.adapter.action.REQUEST_DISCOVERABLE 2>/dev/null || true")
# 等待 hook 投递日志
time.sleep(4)

print("\n=== 5. 蓝牙栈 Hook 日志 ===")
pid = ""
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2:
        pid = parts[1]
print("bt_pid=", pid)
if pid:
    print(sh(f"logcat -d --pid={pid} -t 300 | grep -iE 'ZVirtualEnv|ble stack|Hook'"))
