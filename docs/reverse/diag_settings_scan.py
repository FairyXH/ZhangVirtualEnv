#!/usr/bin/env python3
"""检查蓝牙设置页进程是否触发 LE startScan + 控制端 App 环境页。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 蓝牙设置页进程 ===")
print(sh("ps -A | grep -iE 'settings|wirelesssettings' | head -5"))

print("\n=== 2. 触发设置页附近设备扫描（点进蓝牙列表） ===")
sh("am start -a android.settings.BLUETOOTH_SETTINGS")
time.sleep(6)
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
for m in re.finditer(r'text="([^"]{0,30})"', xml):
    t = m.group(1)
    if t.strip():
        print("text:", t)

print("\n=== 3. 最近 logdaemon startScan（设置页进程触发？） ===")
print(sh("su -c 'grep -a \"ble stack startScan\" /data/adb/lspd/log/modules_*.log | tail -6'") or "(none)")
