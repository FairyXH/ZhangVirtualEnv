#!/usr/bin/env python3
"""Reboot 后带等待的最终验证。"""
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
# 等待 ApiServer 就绪
for _ in range(30):
    if "running" in curl("GET", "/api/status"):
        print("API_OK")
        break
    time.sleep(2)
time.sleep(3)

print("\n=== 1. Hook 安装 ===")
print(sh("su -c 'grep -a \"ble stack hooks\" /data/adb/lspd/log/modules_*.log | tail -1'"))

print("\n=== 2. POST 设置 ZVE 设备 ===")
ble_body = json.dumps({
    "devices": [
        {"name": "ZVE-Virtual-Band", "address": "AA:BB:CC:DD:EE:01", "rssi": -55},
        {"name": "ZVE-Virtual-Beacon", "address": "AA:BB:CC:DD:EE:02", "rssi": -70},
    ],
    "bonded": []
}, ensure_ascii=False)
print(curl("POST", "/api/bluetooth/set", ble_body)[:120])
time.sleep(3)
print("GET:", curl("GET", "/api/bluetooth/status")[:200])

print("\n=== 3. 设置页点击测试 ===")
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
else:
    print("BLE button not found")

print("\n=== 4. logdaemon 投递链 ===")
print(sh("su -c 'grep -a \"ble stack\" /data/adb/lspd/log/modules_*.log | tail -10'") or "(none)")

print("\n=== 5. UI 结果（找 ZVE / 设备名） ===")
sh("uiautomator dump /sdcard/ui3.xml")
xml3 = sh("cat /sdcard/ui3.xml")
found = False
for mm in re.finditer(r'text="([^"]*)"', xml3):
    t = mm.group(1)
    if any(k in t for k in ["ZVE", "AA:BB", "RSSI"]):
        found = True
        print(" ", t)
if not found:
    print("(no device lines)")
    for mm in re.finditer(r'text="(扫描[^"]*)"', xml3):
        print(" result:", mm.group(1))
