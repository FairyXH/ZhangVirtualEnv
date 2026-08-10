#!/usr/bin/env python3
"""检查蓝牙进程模块类加载 + lspd verbose 完整日志。"""
import subprocess


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 蓝牙进程 maps 中模块 dex ===")
print(sh("su -c 'grep -iE \"VirtualEnv|fairyxh\" /proc/7449/maps | head -5'") or "(not mapped)")

print("\n=== 2. 蓝牙进程线程列表 ===")
print(sh("su -c 'ls /proc/7449/task | head -5'"))

print("\n=== 3. lspd verbose log 完整（VirtualEnv + bluetooth） ===")
print(sh("su -c 'grep -a -iE \"VirtualEnv\" /data/adb/lspd/log/verbose_*.log | grep -iE \"bluetooth|Transitional|startScan|hooked|not found|BleStack\" | tail -30'"))

print("\n=== 4. modules log 中 bluetooth 进程全部 VirtualEnv 行 ===")
print(sh("su -c 'grep -a \"(com.android.bluetooth)\" /data/adb/lspd/log/modules_*.log | grep -i virtualenv | tail -20'"))
