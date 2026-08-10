#!/usr/bin/env python3
"""等待设备启动完成并验证模块状态。"""
import subprocess
import time


def sh(cmd: str) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=60)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("waiting for device...")
subprocess.run(["adb", "wait-for-device"], capture_output=True, timeout=120)
for _ in range(90):
    boot = sh("getprop sys.boot_completed")
    if boot == "1":
        print("BOOT_OK")
        break
    time.sleep(2)
else:
    print("BOOT TIMEOUT")

time.sleep(8)
print("\n=== bluetooth / system_server / gms processes ===")
print(sh("ps -A | grep -E 'com.android.bluetooth|system_server|fairyxh'"))

print("\n=== bluetooth pid libxposed maps ===")
bt_pid = ""
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2:
        bt_pid = parts[1]
print("bt_pid=", bt_pid)
if bt_pid:
    print(sh(f"su -c 'grep -E \"libxposed|lspd\" /proc/{bt_pid}/maps | head -5'"))
    print(sh(f"logcat -d --pid={bt_pid} -t 100 | grep -iE 'ZVirtualEnv|Entry|Hook'"))

print("\n=== lspd modules log tail (VirtualEnv) ===")
print(sh("su -c 'grep -a -i \"virtualenv\" /data/adb/lspd/log/modules_*.log | tail -20'"))

print("\n=== dropbox crash check ===")
print(sh("su -c 'ls -t /data/system/dropbox/ 2>/dev/null | grep -E \"system_server_crash|system_app_crash\" | head -5'"))
