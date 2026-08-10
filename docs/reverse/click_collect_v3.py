#!/usr/bin/env python3
"""点击一键采集，实时抓日志，检查蓝牙 Hook 投递。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


# 确保 App 前台
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(3)

# 清空日志
sh("logcat -c")

proc = subprocess.Popen(
    ["adb", "logcat", "-v", "time"],
    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    text=True, errors="replace",
)
time.sleep(1)
print("tapping...")
sh("input tap 260 1200")
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

print("total lines:", len(lines))
print("\n=== ZVirtualEnv 相关 ===")
for l in lines:
    if "ZVirtualEnv" in l or "BluetoothLeScanner" in l or "ble stack" in l:
        print(l)
print("\n=== 蓝牙进程 ZVirtualEnv ===")
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        pid = parts[1]
        out = sh(f"logcat -d --pid={pid} | grep -iE 'ZVirtualEnv|ble stack' | tail -10")
        print(f"bt_pid={pid}: {out if out else '(none)'}")
