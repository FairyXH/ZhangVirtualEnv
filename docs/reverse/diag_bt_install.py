#!/usr/bin/env python3
"""在 lspd modules/verbose 日志中搜索 Bluetooth Hook 安装细节。"""
import subprocess


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. modules log 中 TransitionalScanHelper / class not found / hooked ===")
print(sh("su -c 'grep -a -iE \"TransitionalScanHelper|class not found|hooked|startScan candidates|ble stack\" /data/adb/lspd/log/modules_*.log | tail -30'"))

print("\n=== 2. verbose log 中 TransitionalScanHelper ===")
print(sh("su -c 'grep -a -iE \"TransitionalScanHelper|ScanController|BluetoothScanBinder\" /data/adb/lspd/log/verbose_*.log | tail -20'"))

print("\n=== 3. 蓝牙进程当前 maps 完整扫描（找 Bluetooth APK 与模块） ===")
print(sh("su -c 'grep -iE \"bluetooth|fairyxh|VirtualEnv\" /proc/7449/maps | head -10'"))
