#!/usr/bin/env python3
"""查 lspd verbose 中蓝牙进程 ZLog 输出（rawGet/hooked），确认 Hook 是否拦截。"""
import subprocess


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. lspd verbose log 蓝牙进程全部（含 ZLog 重定向？） ===")
print(sh("su -c 'grep -a \"com.android.bluetooth\" /data/adb/lspd/log/verbose_*.log | tail -30'") or "(none)")

print("\n=== 2. lspd verbose 中 rawGet / EnvCache / hooked 文本 ===")
print(sh("su -c 'grep -a -iE \"rawGet|EnvCache|hooked|ble stack|startScan\" /data/adb/lspd/log/verbose_*.log | tail -20'") or "(none)")

print("\n=== 3. 8350 进程线程与 Hook 日志 ===")
print("ZVE thread:", sh("su -c 'for t in /proc/8350/task/*/comm; do cat $t 2>/dev/null; done' | grep -i ZVE") or "(none)")
print(sh("logcat -d --pid=8350 | grep -iE 'ZVirtualEnv|ble stack' | tail -5") or "(no ZVirtualEnv)")

print("\n=== 4. 蓝牙进程 logs 目录（LSPosed 独立日志） ===")
print(sh("su -c 'ls -la /data/adb/lspd/log/'"))
