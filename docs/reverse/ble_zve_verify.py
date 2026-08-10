#!/usr/bin/env python3
"""验证：POST ZVE 设备 → GET 确认 → 设置页测试 → UI 应显示 ZVE。"""
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


print("=== 1. POST 设置 ZVE 设备 ===")
ble_body = json.dumps({
    "devices": [
        {"name": "ZVE-Virtual-Band", "address": "AA:BB:CC:DD:EE:01", "rssi": -55},
        {"name": "ZVE-Virtual-Beacon", "address": "AA:BB:CC:DD:EE:02", "rssi": -70},
    ],
    "bonded": []
}, ensure_ascii=False)
print(curl("POST", "/api/bluetooth/set", ble_body)[:150])
time.sleep(3)
print("GET after set:", curl("GET", "/api/bluetooth/status")[:250])

print("\n=== 2. 打开设置页点测试 ===")
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(7)
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
m = re.search(r'text="设置"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    sh(f"input tap {x} {y}")
    time.sleep(4)
sh("uiautomator dump /sdcard/ui2.xml")
xml2 = sh("cat /sdcard/ui2.xml")
m2 = re.search(r'text="开始扫描 8 秒"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml2)
if not m2:
    sh("input swipe 632 2200 632 600 500")
    time.sleep(3)
    sh("uiautomator dump /sdcard/ui2.xml")
    xml2 = sh("cat /sdcard/ui2.xml")
    m2 = re.search(r'text="开始扫描 8 秒"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml2)
if m2:
    x = (int(m2.group(1)) + int(m2.group(3))) // 2
    y = (int(m2.group(2)) + int(m2.group(4))) // 2
    print(f"tap BLE test {x},{y}")
    sh(f"input tap {x} {y}")
    time.sleep(12)

print("\n=== 3. logdaemon ===")
print(sh("su -c 'grep -a \"ble stack\" /data/adb/lspd/log/modules_*.log | tail -8'") or "(none)")

print("\n=== 4. UI 结果（找 ZVE） ===")
sh("uiautomator dump /sdcard/ui3.xml")
xml3 = sh("cat /sdcard/ui3.xml")
found = False
for mm in re.finditer(r'text="([^"]*)"', xml3):
    t = mm.group(1)
    if "ZVE" in t or "AA:BB" in t:
        found = True
        print(" ", t)
if not found:
    print("(no ZVE in UI)")
    # 打印结果区全部
    for mm in re.finditer(r'text="(扫描[^"]*)"', xml3):
        print(" result:", mm.group(1))
