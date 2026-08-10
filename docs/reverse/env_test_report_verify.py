#!/usr/bin/env python3
"""Reboot 后完整验证：设置虚拟环境 → 环境测试 → 六栏判定 → /api/test/report 读取。"""
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
subprocess.run(["adb", "wait-for-device"], capture_output=True, timeout=180)
for _ in range(120):
    if sh("getprop sys.boot_completed") == "1":
        print("BOOT_OK")
        break
    time.sleep(2)
for _ in range(30):
    if "running" in curl("GET", "/api/status"):
        print("API_OK")
        break
    time.sleep(2)

print("\n=== 1. 设置虚拟环境 ===")
print("loc:", curl("POST", "/api/location/set", json.dumps({"latitude": 24.6477, "longitude": 118.2993, "speed": 0, "bearing": 0}))[:60])
print("loc on:", curl("POST", "/api/location/enable", json.dumps({"enabled": True}))[:60])
print("cell:", curl("POST", "/api/cell/set", json.dumps({"entries": [{"type": "LTE", "mcc": 460, "mnc": 11, "tac": 6176256, "ci": 25326404610}]}))[:60])
print("ble:", curl("POST", "/api/bluetooth/set", json.dumps({"devices": [{"name": "ZVE-Virtual-Band", "address": "AA:BB:CC:DD:EE:01", "rssi": -55}], "bonded": []}))[:60])
print("wifi:", curl("POST", "/api/wifi/set", json.dumps({"networks": [{"ssid": "ZVE-Virtual-WiFi", "bssid": "AA:BB:CC:00:00:01", "level": -50}]}))[:60])
print("sensor:", curl("POST", "/api/sensor/set", json.dumps({"stepFrequency": 120}))[:60])
print("gnss:", curl("POST", "/api/gnss/set", json.dumps({"satelliteCount": 20, "usedInFix": 8}))[:60])
time.sleep(3)

print("\n=== 2. 打开设置页启动测试 ===")
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(8)
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
m = re.search(r'text="设置"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    sh(f"input tap {x} {y}")
    time.sleep(4)
for _ in range(4):
    sh("uiautomator dump /sdcard/ui.xml")
    xml = sh("cat /sdcard/ui.xml")
    m2 = re.search(r'text="开始"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m2:
        x = (int(m2.group(1)) + int(m2.group(3))) // 2
        y = (int(m2.group(2)) + int(m2.group(4))) // 2
        sh(f"input tap {x} {y}")
        print(f"tap 开始 {x},{y}")
        break
    sh("input swipe 632 2200 632 800 400")
    time.sleep(1)
time.sleep(12)

print("\n=== 3. 读取 /api/test/report ===")
report = curl("GET", "/api/test/report")
try:
    j = json.loads(report)
    print(json.dumps(j, ensure_ascii=False, indent=1)[:2500])
except Exception as e:
    print("parse fail:", e, report[:300])

print("\n=== 4. UI 判定徽标 ===")
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
for label in ["位置", "基站", "蓝牙", "WiFi", "传感器", "GNSS"]:
    m = re.search(rf'text="{label}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        print(f"{label}: (not visible)")
        continue
    y0 = (int(m.group(2)) + int(m.group(4))) // 2
    status = "?"
    for mm in re.finditer(r'text="(通过|未通过|未启用模拟)"', xml):
        sy = None
        mm2 = re.search(rf'text="{mm.group(1)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if mm2:
            sy = (int(mm2.group(3)) + int(mm2.group(5))) // 2
        if sy is not None and abs(sy - y0) < 60:
            status = mm.group(1)
            break
    print(f"{label}: [{status}]")
