#!/usr/bin/env python3
"""尝试系统 Settings 附近设备扫描（LE）触发虚拟 BLE 投递。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


# 记录当前 logdaemon startScan 计数
before = sh("su -c 'grep -ac \"ble stack startScan\" /data/adb/lspd/log/modules_*.log'")
print("before count:", before)

print("=== 1. 尝试系统 Settings 蓝牙页 ===")
sh("am start -n com.android.settings/.bluetooth.BluetoothSettings 2>&1 || true")
time.sleep(8)
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
print("settings page texts:")
for m in re.finditer(r'text="([^"]{0,30})"', xml):
    t = m.group(1)
    if t.strip():
        print(" ", t)

print("\n=== 2. 若有 附近设备/刷新 点击 ===")
for label in ["附近设备", "刷新", "搜索设备", "扫描"]:
    m = re.search(rf'text="{label}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        x = (int(m.group(1)) + int(m.group(3))) // 2
        y = (int(m.group(2)) + int(m.group(4))) // 2
        print(f"tap {label} at {x},{y}")
        sh(f"input tap {x} {y}")
        time.sleep(10)
        break

print("\n=== 3. 新 startScan 日志 ===")
after = sh("su -c 'grep -ac \"ble stack startScan\" /data/adb/lspd/log/modules_*.log'")
print("after count:", after)
if int(after or 0) > int(before or 0):
    print(sh("su -c 'grep -a \"ble stack startScan\" /data/adb/lspd/log/modules_*.log | tail -6'"))
else:
    print("(no new startScan)")
