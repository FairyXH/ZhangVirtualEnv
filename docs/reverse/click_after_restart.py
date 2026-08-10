#!/usr/bin/env python3
"""杀 App 重启 → 触发采集 → 观察 BLE Hook 日志。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("kill & restart app")
sh("am force-stop io.github.fairyxh.VirtualEnv")
time.sleep(2)
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(8)

sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
m = re.search(r'text="开始采集"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if not m:
    print("开始采集 not found")
    exit(1)
x = (int(m.group(1)) + int(m.group(3))) // 2
y = (int(m.group(2)) + int(m.group(4))) // 2
print(f"开始采集 at {x},{y}")

sh("logcat -c")
proc = subprocess.Popen(
    ["adb", "logcat", "-v", "time"],
    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    text=True, errors="replace",
)
time.sleep(1)
sh(f"input tap {x} {y}")
time.sleep(20)
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
print("\n=== 相关日志 ===")
for l in lines:
    if any(k in l for k in ["ZVirtualEnv", "BluetoothLeScanner", "ble stack", "startScan", "onScannerRegistered", "scan started", "Collect"]):
        print(l)
