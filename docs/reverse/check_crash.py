#!/usr/bin/env python3
"""检查 dropbox crash 文件时间与内容摘要，确认是否本次 reboot 新崩溃。"""
import subprocess
from datetime import datetime, timezone


def sh(cmd: str) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=60)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 设备当前时间 ===")
now = sh("date +%s")
print("epoch:", now)
try:
    print("human:", datetime.fromtimestamp(int(now), timezone.utc).isoformat())
except Exception:
    pass

print("\n=== 最新 dropbox crash 文件(带时间) ===")
print(sh("su -c 'ls -lt /data/system/dropbox/ | grep -E \"crash\" | head -8'"))

print("\n=== 最新 system_server_crash 摘要 ===")
f = sh("su -c 'ls -t /data/system/dropbox/ | grep system_server_crash | head -1'")
if f:
    print("file:", f)
    head = sh(f"su -c 'head -c 2000 /data/system/dropbox/{f}'")
    print(head)

print("\n=== 最新 system_app_crash 摘要 ===")
f2 = sh("su -c 'ls -t /data/system/dropbox/ | grep system_app_crash | head -1'")
if f2:
    print("file:", f2)
    head2 = sh(f"su -c 'head -c 1500 /data/system/dropbox/{f2}'")
    print(head2)

print("\n=== LSPosed 安全模式状态 ===")
print(sh("su -c 'cat /data/adb/lspd/config/disable 2>/dev/null || echo no-disable-file'"))
print(sh("su -c 'getprop persist.lsposed.safemode 2>/dev/null || echo no-prop'"))
