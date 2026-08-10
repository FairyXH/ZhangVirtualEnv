#!/usr/bin/env python3
"""设置虚拟 BLE → 打开设置页 → 点击 BLE 扫描测试按钮 → 验证虚拟设备投递。"""
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


print("=== 1. 确认 Boot + ApiServer ===")
print(curl("GET", "/api/status")[:120])

print("\n=== 2. 设置虚拟 BLE ===")
ble_body = json.dumps({
    "devices": [
        {"name": "ZVE-Virtual-Band", "address": "AA:BB:CC:DD:EE:01", "rssi": -55},
        {"name": "ZVE-Virtual-Beacon", "address": "AA:BB:CC:DD:EE:02", "rssi": -70},
    ],
    "bonded": []
}, ensure_ascii=False)
print(curl("POST", "/api/bluetooth/set", ble_body)[:180])
time.sleep(4)
print("status:", curl("GET", "/api/bluetooth/status")[:180])

print("\n=== 3. 启动 App 打开设置页 ===")
sh("am force-stop io.github.fairyxh.VirtualEnv")
time.sleep(2)
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(7)
# 点击底部 设置 tab
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
m = re.search(r'text="设置"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    print(f"tap 设置 at {x},{y}")
    sh(f"input tap {x} {y}")
    time.sleep(4)

print("\n=== 4. 找到 BLE 测试按钮 ===")
sh("uiautomator dump /sdcard/ui2.xml")
xml2 = sh("cat /sdcard/ui2.xml")
m2 = re.search(r'text="开始扫描 8 秒"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml2)
if not m2:
    # 滚动到底部
    sh("input swipe 632 2000 632 500 500")
    time.sleep(3)
    sh("uiautomator dump /sdcard/ui2.xml")
    xml2 = sh("cat /sdcard/ui2.xml")
    m2 = re.search(r'text="开始扫描 8 秒"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml2)
if m2:
    x = (int(m2.group(1)) + int(m2.group(3))) // 2
    y = (int(m2.group(2)) + int(m2.group(4))) // 2
    print(f"BLE test button at {x},{y}")
    # 点击前清空 logcat
    sh("logcat -c")
    print("tap start scan")
    sh(f"input tap {x} {y}")
    time.sleep(12)
else:
    print("BLE test button not found")
    print(xml2[:500])

print("\n=== 5. logdaemon startScan（验证 Hook 投递） ===")
print(sh("su -c 'grep -a \"ble stack startScan\" /data/adb/lspd/log/modules_*.log | tail -6'") or "(none)")

print("\n=== 6. App 测试结果 UI ===")
sh("uiautomator dump /sdcard/ui3.xml")
xml3 = sh("cat /sdcard/ui3.xml")
m3 = re.search(r'text="(ZVE[^\"]*)"', xml3)
print("virtual device in UI:", bool(m3))
for mm in re.finditer(r'text="([^"]*ZVE[^"]*)"', xml3):
    print(" ", mm.group(1))

print("\n=== 7. App 日志 ===")
app = ""
for line in sh("ps -A | grep fairyxh.VirtualEnv").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        app = parts[1]
print("app_pid=", app)
if app:
    print(sh(f"logcat -d --pid={app} -t 200 | grep -iE 'ble test|scan' | tail -15") or "(no ble test logs)")
