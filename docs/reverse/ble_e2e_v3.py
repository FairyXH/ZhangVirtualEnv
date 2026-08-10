#!/usr/bin/env python3
"""Reboot 后验证多入口 Hook 计数 + BLE 端到端。"""
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

print("\n=== 1. Hook 安装计数 ===")
print(sh("su -c 'grep -a \"ble stack hooks\" /data/adb/lspd/log/modules_*.log | tail -3'"))

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

print("\n=== 3. 触发 App 采集 ===")
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(4)
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
m = re.search(r'text="一键采集"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    print(f"tap {x},{y}")
    sh("logcat -c")
    proc = subprocess.Popen(
        ["adb", "logcat", "-v", "time"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, errors="replace",
    )
    time.sleep(1)
    sh(f"input tap {x} {y}")
    time.sleep(18)
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
    print("\n=== 4. 扫描相关日志 ===")
    for l in lines:
        if any(k in l for k in ["ZVirtualEnv", "BluetoothLeScanner", "ble stack", "startScan", "onScannerRegistered", "scan started"]):
            print(l)

print("\n=== 5. 蓝牙进程诊断日志 ===")
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and "com.android.bluetooth" in line:
        pid = parts[1]
        out = sh(f"logcat -d --pid={pid} | grep -iE 'ZVirtualEnv|ble stack|hooked' | tail -15")
        print(f"bt_pid={pid}:")
        print(out if out else "(none)")
