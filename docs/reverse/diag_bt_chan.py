#!/usr/bin/env python3
"""确认蓝牙进程 ZLog 通道与 EnvCache 轮询。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


pid = ""
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        pid = parts[1]
print("bt_pid=", pid)

print("\n=== 1. 蓝牙进程 logcat 任意 java 日志（确认通道） ===")
print(sh(f"logcat -d --pid={pid} | grep -vE 'bt_|bluetooth:|OplusAdapter' | tail -20"))

print("\n=== 2. 全局 logcat 中该 PID 的 ZVirtualEnv ===")
print(sh(f"logcat -d | grep ' {pid} ' | grep ZVirtualEnv | tail -10") or "(none)")

print("\n=== 3. 清空 logcat 等 5 秒（EnvCache 轮询应每 2s 输出失败日志） ===")
sh("logcat -c")
time.sleep(6)
print(sh(f"logcat -d | grep ' {pid} ' | grep -iE 'ZVirtualEnv|EnvCache|rawGet' | tail -10") or "(no ZVirtualEnv after 6s)")

print("\n=== 4. lspd modules log 蓝牙进程最近 ===")
print(sh("su -c 'grep -a \"(com.android.bluetooth)\" /data/adb/lspd/log/modules_*.log | grep -i virtualenv | tail -5'"))
