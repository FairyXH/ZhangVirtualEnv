#!/usr/bin/env python3
"""定位 LSPosed 安装路径与模块 scope 数据库。"""
import subprocess

def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()

print("=== /data/adb recursive ===")
print(sh("adb shell su -c 'ls -la /data/adb 2>/dev/null; find /data/adb -maxdepth 2 -name \"*.db\" -o -maxdepth 2 -name \"*.list\" 2>/dev/null | head -30'"))
print("=== search lspd anywhere ===")
print(sh("adb shell su -c 'find /data -maxdepth 4 -iname \"*lspd*\" 2>/dev/null | head -20'"))
print("=== search modules_config ===")
print(sh("adb shell su -c 'find /data -maxdepth 5 -name \"modules_config*\" 2>/dev/null | head -10'"))
print("=== zygisk module list ===")
print(sh("adb shell su -c 'find /data/adb/modules -maxdepth 2 -type d 2>/dev/null | head -20'"))
