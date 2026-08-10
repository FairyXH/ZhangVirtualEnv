#!/usr/bin/env python3
"""确认当前 com.android.bluetooth PID 22592 的 LSPosed 注入状态。"""
import subprocess


def sh(cmd: str) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=60)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 当前 bluetooth PID ===")
print(sh("ps -A | grep com.android.bluetooth"))

print("\n=== 2. maps 中是否有 lspd/xposed 相关 ===")
print(sh("su -c 'grep -iE \"lspd|xposed|VirtualEnv\" /proc/22592/maps' | head -20"))

print("\n=== 3. maps 中是否有 fairyxh apk ===")
print(sh("su -c 'grep -iE \"fairyxh|VirtualEnv\" /proc/22592/maps' | head -20"))

print("\n=== 4. 当前 bluetooth 进程 logcat ZVirtualEnv ===")
print(sh("logcat -d --pid=22592 | grep -iE 'ZVirtualEnv|LSPosed' | head -30"))

print("\n=== 5. 最新 modules log (14:xx) ===")
print(sh("su -c 'ls -la /data/adb/lspd/log/'"))

print("\n=== 6. 最新 modules log 全文尾部 ===")
print(sh("su -c 'tail -60 /data/adb/lspd/log/modules_*.log'"))
