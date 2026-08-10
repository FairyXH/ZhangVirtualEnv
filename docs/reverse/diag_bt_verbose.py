#!/usr/bin/env python3
"""深入检查蓝牙进程模块安装期日志（lspd verbose + 启动期 logcat）。"""
import subprocess


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. lspd verbose 中 VirtualEnv bluetooth 附近（前后文） ===")
out = sh("su -c 'grep -a -n \"com.android.bluetooth\" /data/adb/lspd/log/verbose_*.log | head -20'")
print(out or "(none)")

print("\n=== 2. 完整 verbose 中 VirtualEnv 相关 ===")
print(sh("su -c 'grep -a \"VirtualEnv\" /data/adb/lspd/log/verbose_*.log | tail -40'"))

print("\n=== 3. logcat main buffer 早期（boot 时）ZVirtualEnv ===")
print(sh("logcat -d -b main | grep ZVirtualEnv | tail -30"))
