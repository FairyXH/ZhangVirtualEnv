#!/usr/bin/env python3
"""全局搜索蓝牙进程 ZVirtualEnv 日志 + 确认扫描链路。"""
import subprocess


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 全局 logcat 中 ZVirtualEnv（带 PID） ===")
print(sh("logcat -d -t 3000 | grep ZVirtualEnv | tail -30") or "(none)")

print("\n=== 2. 蓝牙进程 7459 全部日志中 ZVirtualEnv/hooked ===")
print(sh("logcat -d | grep ' 7459 ' | grep -iE 'ZVirtualEnv|hooked|ble stack|class not found' | tail -20") or "(none)")

print("\n=== 3. lspd modules log 蓝牙进程 hook 细节 ===")
print(sh("su -c 'grep -a \"(com.android.bluetooth)\" /data/adb/lspd/log/modules_*.log | grep -i virtualenv | tail -6'"))

print("\n=== 4. 蓝牙进程 7459 完整线程（找 ScanController/BTScanManager） ===")
print(sh("su -c 'for t in /proc/7459/task/*/comm; do cat $t 2>/dev/null; done' | sort | uniq -c | sort -rn | head -50"))
