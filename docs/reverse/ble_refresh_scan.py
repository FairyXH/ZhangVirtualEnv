#!/usr/bin/env python3
"""点击设置页刷新按钮强制扫描，监控 logdaemon LE startScan 投递。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


# 记录当前 logdaemon 行数（用时间戳区分）
before = sh("su -c 'grep -ac \"ble stack startScan\" /data/adb/lspd/log/modules_*.log'")

sh("am start -a android.settings.BLUETOOTH_SETTINGS")
time.sleep(5)
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")

# 找 刷新 按钮
m = re.search(r'text="刷新"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    print(f"刷新 at {x},{y}")
    sh(f"input tap {x} {y}")
    time.sleep(12)
else:
    print("刷新 not found, scrolling up")
    sh("input swipe 632 2000 632 1000 300")
    time.sleep(3)
    sh("uiautomator dump /sdcard/ui.xml")
    xml = sh("cat /sdcard/ui.xml")
    m = re.search(r'text="刷新"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        x = (int(m.group(1)) + int(m.group(3))) // 2
        y = (int(m.group(2)) + int(m.group(4))) // 2
        print(f"刷新 after scroll at {x},{y}")
        sh(f"input tap {x} {y}")
        time.sleep(12)

print("\n=== 最新 logdaemon startScan（15:1x） ===")
print(sh("su -c 'grep -a \"ble stack startScan\" /data/adb/lspd/log/modules_*.log | tail -10'") or "(none)")

print("\n=== 设置页进程 BluetoothLeScanner 日志 ===")
settings_pid = ""
for line in sh("ps -A | grep com.oplus.wirelesssettings").splitlines():
    parts = line.split()
    if len(parts) >= 2:
        settings_pid = parts[1]
print("settings_pid=", settings_pid)
if settings_pid:
    print(sh(f"logcat -d --pid={settings_pid} -t 200 | grep -iE 'BluetoothLeScanner|startScan|scan' | tail -15") or "(no scan logs)")
