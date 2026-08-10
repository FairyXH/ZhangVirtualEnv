#!/usr/bin/env python3
"""检查所有 logcat buffer 中 ZVirtualEnv + 验证 WiFi 虚拟化。"""
import subprocess
import json
import time


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


def curl(method: str, path: str, body: str = "") -> str:
    if body:
        cmd = f'curl -s -X {method} -H "Content-Type: application/json" -d \'{body}\' http://127.0.0.1:18790{path}'
    else:
        cmd = f'curl -s -X {method} http://127.0.0.1:18790{path}'
    return sh(cmd)


print("=== 1. 设置虚拟 WiFi ===")
wifi_body = json.dumps({"networks": [{"ssid": "ZVE-VirtualWiFi", "bssid": "00:11:22:33:44:55", "rssi": -50, "frequency": 2412}]}, ensure_ascii=False)
print(curl("POST", "/api/wifi/set", wifi_body)[:300])

print("\n=== 2. logcat all buffers ZVirtualEnv ===")
for buf in ["main", "system", "events", "crash"]:
    out = sh(f"logcat -d -b {buf} -t 500 | grep ZVirtualEnv | tail -5")
    print(f"[{buf}] {out if out else '(none)'}")

print("\n=== 3. dumpsys wifi 前几个 scan results（应含虚拟 WiFi） ===")
time.sleep(2)
print(sh("dumpsys wifi | grep -iE 'ZVE-VirtualWiFi|00:11:22:33:44:55' | head -5"))
