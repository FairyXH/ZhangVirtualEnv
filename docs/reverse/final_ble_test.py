#!/usr/bin/env python3
"""终验 BLE：重新设置虚拟数据 → 触发采集扫描 → 观察 Hook 投递。"""
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


print("=== 1. 设置虚拟 BLE ===")
ble_body = json.dumps({
    "devices": [
        {"name": "ZVE-Virtual-Band", "address": "AA:BB:CC:DD:EE:01", "rssi": -55},
        {"name": "ZVE-Virtual-Beacon", "address": "AA:BB:CC:DD:EE:02", "rssi": -70},
    ],
    "bonded": []
}, ensure_ascii=False)
print(curl("POST", "/api/bluetooth/set", ble_body)[:300])

print("\n=== 2. 等待 EnvCache 轮询（2s 间隔） ===")
time.sleep(6)
print("env status ble:", curl("GET", "/api/env/status")[:300])

print("\n=== 3. 回到控制端 App 采集页并触发一键采集 ===")
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(3)
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
import re
# 找 一键采集 按钮
m = re.search(r'text="一键采集"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    print(f"tapping 一键采集 at {x},{y}")
    # 实时捕获日志
    proc = subprocess.Popen(
        ["adb", "logcat", "-v", "time", "-s", "ZVirtualEnv:*"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, errors="replace",
    )
    time.sleep(1)
    sh(f"input tap {x} {y}")
    time.sleep(15)
    proc.terminate()
    lines = []
    try:
        while True:
            line = proc.stdout.readline()
            if not line:
                break
            lines.append(line.strip())
    except Exception:
        pass
    print("\n=== 4. 采集期间日志 ===")
    print("\n".join(lines[-60:]) if lines else "(none)")
else:
    print("一键采集 button not found")
