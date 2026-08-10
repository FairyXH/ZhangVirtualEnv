#!/usr/bin/env python3
"""Reboot 后检查蓝牙 Hook 安装细节（hooked 计数）。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("waiting for boot...")
subprocess.run(["adb", "wait-for-device"], capture_output=True, timeout=120)
for _ in range(90):
    if sh("getprop sys.boot_completed") == "1":
        print("BOOT_OK")
        break
    time.sleep(2)
time.sleep(10)

print("\n=== lspd modules log: 蓝牙/phone hooked 计数 ===")
print(sh("su -c 'grep -a -iE \"ble stack hooks|phone interface manager hooks|hooked=\" /data/adb/lspd/log/modules_*.log | tail -10'"))

print("\n=== 蓝牙进程 logcat（安装期） ===")
pid = ""
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        pid = parts[1]
print("bt_pid=", pid)
if pid:
    print(sh(f"logcat -d --pid={pid} -t 600 | grep -iE 'ZVirtualEnv|hooked|class not found' | tail -20"))
