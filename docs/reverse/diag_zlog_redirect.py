#!/usr/bin/env python3
"""检查 lspd log 中是否有蓝牙进程的 ZLog（rawGet failed / hooked）输出。"""
import subprocess


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. modules log 中 rawGet / EnvCache / hooked ===")
print(sh("su -c 'grep -a -iE \"rawGet|EnvCache|hooked|class not found\" /data/adb/lspd/log/modules_*.log | grep -i virtualenv | tail -20'") or "(none)")

print("\n=== 2. modules log 蓝牙进程后 5 分钟全部行 ===")
print(sh("su -c 'grep -a \"(com.android.bluetooth)\" /data/adb/lspd/log/modules_*.log | tail -30'"))

print("\n=== 3. verbose log 蓝牙进程 ===")
print(sh("su -c 'grep -a \"com.android.bluetooth\" /data/adb/lspd/log/verbose_*.log | grep -i virtualenv | tail -20'") or "(none)")
