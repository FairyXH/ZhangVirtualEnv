#!/usr/bin/env python3
"""Reboot 后：确认 logdaemon 输出 Hook 调用情况 + 触发采集。"""
import subprocess
import time
import re
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


print("waiting boot...")
subprocess.run(["adb", "wait-for-device"], capture_output=True, timeout=120)
for _ in range(90):
    if sh("getprop sys.boot_completed") == "1":
        print("BOOT_OK")
        break
    time.sleep(2)
time.sleep(10)

print("\n=== 1. Hook 安装 logdaemon ===")
print(sh("su -c 'grep -a \"ble stack hooks\\|ble install\" /data/adb/lspd/log/modules_*.log | tail -5'"))

print("\n=== 2. 设置虚拟 BLE ===")
ble_body = json.dumps({
    "devices": [
        {"name": "ZVE-Virtual-Band", "address": "AA:BB:CC:DD:EE:01", "rssi": -55},
        {"name": "ZVE-Virtual-Beacon", "address": "AA:BB:CC:DD:EE:02", "rssi": -70},
    ],
    "bonded": []
}, ensure_ascii=False)
print(curl("POST", "/api/bluetooth/set", ble_body)[:150])
time.sleep(5)

print("\n=== 3. 启动 App 并点击采集 ===")
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(8)
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
m = re.search(r'text="开始采集"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    print(f"tap 开始采集 {x},{y}")
    sh(f"input tap {x} {y}")
    time.sleep(15)

print("\n=== 4. logdaemon: Hook invoked 日志 ===")
print(sh("su -c 'grep -a \"ble stack startScan\" /data/adb/lspd/log/modules_*.log | tail -10'") or "(no startScan logs)")

print("\n=== 5. 蓝牙进程 logcat ===")
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and "com.android.bluetooth" in line:
        pid = parts[1]
        out = sh(f"logcat -d --pid={pid} -t 400 | grep -iE 'ZVirtualEnv|ble stack|hooked|scan' | tail -20")
        print(f"\nbt_pid={pid}:")
        print(out if out else "(none)")
