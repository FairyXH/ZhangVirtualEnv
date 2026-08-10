#!/usr/bin/env python3
"""更大范围搜索 ZVirtualEnv 日志。"""
import subprocess


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 全局 logcat -t 5000 ZVirtualEnv ===")
print(sh("logcat -d -t 5000 | grep ZVirtualEnv | tail -40"))

print("\n=== lspd verbose log 中 VirtualEnv 蓝牙相关 ===")
print(sh("su -c 'grep -a -iE \"virtualenv|TransitionalScanHelper|ble stack\" /data/adb/lspd/log/verbose_*.log | tail -40'"))
