#!/usr/bin/env python3
"""全局搜索 ZVirtualEnv 日志与蓝牙 hook 状态。"""
import subprocess


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 全局 logcat ZVirtualEnv（不限 pid） ===")
print(sh("logcat -d | grep -E 'ZVirtualEnv' | tail -40"))

print("\n=== logcat 全部 buffer 中 TransitionalScanHelper ===")
print(sh("logcat -d | grep -E 'TransitionalScanHelper|ble stack' | tail -20"))

print("\n=== 蓝牙进程 maps 中模块 APK ===")
print(sh("su -c 'grep -i fairyxh /proc/7449/maps | head -5'"))
