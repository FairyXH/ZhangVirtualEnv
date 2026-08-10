#!/usr/bin/env python3
"""蓝牙/LSPosed 注入诊断：检查 com.android.bluetooth 是否被 LSPosed 加载模块。"""
import subprocess


def sh(cmd: str) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=60)
    out = p.stdout.decode("utf-8", errors="replace")
    err = p.stderr.decode("utf-8", errors="replace")
    return (out + ("\n[STDERR] " + err if err else "")).strip()


print("=== 1. bluetooth 进程启动时间 ===")
print(sh("ps -A -o PID,PPID,NAME,ELAPSED | grep -E 'bluetooth|fairyxh'"))

print("\n=== 2. lspd 目录 ===")
print(sh("su -c 'ls -la /data/adb/lspd/'"))

print("\n=== 3. lspd logs ===")
print(sh("su -c 'ls -la /data/adb/lspd/logs/ 2>/dev/null || ls -la /data/adb/lspd/log/ 2>/dev/null'"))

print("\n=== 4. modules log tail ===")
print(sh("su -c 'tail -50 /data/adb/lspd/logs/modules.log 2>/dev/null || tail -50 /data/adb/lspd/log/modules.log 2>/dev/null'"))

print("\n=== 5. bluetooth 进程 libxposed maps ===")
print(sh("su -c 'grep -E \"libxposed|lspd\" /proc/22592/maps | head -20'"))

print("\n=== 6. lspd 主日志 tail ===")
print(sh("su -c 'tail -100 /data/adb/lspd/logs/lspd.log 2>/dev/null || tail -100 /data/adb/lspd/log/lspd.log 2>/dev/null'"))
