#!/usr/bin/env python3
"""强制重启蓝牙进程，抓取 Hook 安装期日志。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 清空 logcat ===")
sh("logcat -c")

print("=== 杀掉蓝牙进程（系统会自动重启） ===")
sh("su -c 'kill -9 7449'")
time.sleep(8)

print("=== 新蓝牙进程 ===")
print(sh("ps -A | grep com.android.bluetooth"))

print("\n=== 蓝牙进程 ZVirtualEnv 安装日志 ===")
pid = ""
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        pid = parts[1]
print("bt_pid=", pid)
if pid:
    time.sleep(2)
    out = sh(f"logcat -d --pid={pid} | grep -iE 'ZVirtualEnv|class not found|hooked|startScan candidates' | tail -30")
    print(out or "(none)")
    print("\n=== 蓝牙进程完整日志（前 30 行） ===")
    print(sh(f"logcat -d --pid={pid} | tail -30"))
