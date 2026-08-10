#!/usr/bin/env python3
"""直接验证 POST 设置 BLE 后 GET 状态是否一致。"""
import subprocess
import time
import json


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


def curl(method: str, path: str, body: str = "") -> str:
    if body:
        cmd = f'curl -s -X {method} -H "Content-Type: application/json" -d \'{body}\' http://127.0.0.1:18790{path}'
    else:
        cmd = f'curl -s -X {method} http://127.0.0.1:18790{path}'
    return sh(cmd)


print("=== 1. 当前 env status ===")
print(curl("GET", "/api/env/status"))

print("\n=== 2. POST 设置 BLE ===")
ble_body = json.dumps({
    "devices": [
        {"name": "ZVE-Virtual-Band", "address": "AA:BB:CC:DD:EE:01", "rssi": -55},
        {"name": "ZVE-Virtual-Beacon", "address": "AA:BB:CC:DD:EE:02", "rssi": -70},
    ],
    "bonded": []
}, ensure_ascii=False)
print(curl("POST", "/api/bluetooth/set", ble_body))

print("\n=== 3. 立即 GET env status ===")
print(curl("GET", "/api/env/status"))

print("\n=== 4. 等待 3 秒再 GET ===")
time.sleep(3)
print(curl("GET", "/api/env/status"))

print("\n=== 5. system_server ApiServer 日志 ===")
print(sh("logcat -d --pid=$(pidof system_server) | grep -iE 'ApiServer|bluetooth|ble' | tail -20") or "(none)")
