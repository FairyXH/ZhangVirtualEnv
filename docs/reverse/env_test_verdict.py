#!/usr/bin/env python3
"""设置多类型虚拟环境 → 打开环境测试 → 验证判定徽标与原始数据。"""
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


print("=== 1. 设置虚拟环境 ===")
# 位置（用当前真实位置附近，便于判通过）
print("loc:", curl("POST", "/api/location/set", json.dumps({"latitude": 24.6477, "longitude": 118.2993, "speed": 0, "bearing": 0}))[:80])
print("loc enable:", curl("POST", "/api/location/enable", json.dumps({"enabled": True}))[:80])
# 基站
print("cell:", curl("POST", "/api/cell/set", json.dumps({"entries": [{"type": "LTE", "mcc": 460, "mnc": 11, "tac": 6176256, "ci": 25326404610}]}))[:80])
# 蓝牙
print("ble:", curl("POST", "/api/bluetooth/set", json.dumps({"devices": [{"name": "ZVE-Virtual-Band", "address": "AA:BB:CC:DD:EE:01", "rssi": -55}], "bonded": []}))[:80])
# WiFi
print("wifi:", curl("POST", "/api/wifi/set", json.dumps({"networks": [{"ssid": "ZVE-Virtual-WiFi", "bssid": "AA:BB:CC:00:00:01", "level": -50}]}))[:80])
# 传感器
print("sensor:", curl("POST", "/api/sensor/set", json.dumps({"stepFrequency": 120}))[:80])
# GNSS
print("gnss:", curl("POST", "/api/gnss/set", json.dumps({"satelliteCount": 20, "usedInFix": 8}))[:80])
time.sleep(3)
print("env status:", curl("GET", "/api/env/status")[:250])

print("\n=== 2. 打开设置页 ===")
sh("am force-stop io.github.fairyxh.VirtualEnv")
time.sleep(1)
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

print("\n=== 3. 滚动到测试卡片点开始 ===")
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

time.sleep(10)

print("\n=== 4. 读取各栏状态与数据 ===")
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
for label in ["位置", "基站", "蓝牙", "WiFi", "传感器", "GNSS"]:
    m = re.search(rf'text="{label}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        print(f"\n--- {label}: (not visible)")
        continue
    y0 = (int(m.group(2)) + int(m.group(4))) // 2
    # 状态徽标在标题行右侧（同 y 附近）
    status = "?"
    for mm in re.finditer(r'text="(通过|未通过|未启用模拟)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        sy = (int(mm.group(3)) + int(mm.group(5))) // 2
        if abs(sy - y0) < 60:
            status = mm.group(1)
            break
    # 数据区在标题下方
    vals = []
    for mm in re.finditer(r'text="([^"]{4,500})"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t = mm.group(1)
        yy = (int(mm.group(3)) + int(mm.group(5))) // 2
        if 0 < yy - y0 < 300 and t not in ["开始", "结束", "主页", "位置", "路线", "环境", "设置", "未开始"]:
            vals.append(t)
    print(f"\n--- {label} [{status}] ---")
    print("  " + "\n  ".join(vals[:5]) if vals else "  (empty)")
