#!/usr/bin/env python3
"""确认已安装包与 LSPosed 管理器、Magisk 环境。"""
import subprocess

def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()

print("=== installed packages containing fairyxh ===")
print(sh("adb shell pm list packages | grep fairyxh"))
print("=== installed packages containing lsp ===")
print(sh("adb shell pm list packages | grep -i lsp"))
print("=== magisk ===")
print(sh("adb shell su -c 'magisk -v 2>&1'"))
print("=== /data/adb exists? ===")
print(sh("adb shell su -c 'ls -ld /data/adb 2>&1; ls -la /data/adb 2>&1'"))
print("=== /data/adb/lspd (may need root ls) ===")
print(sh("adb shell su -c 'ls -la /data/adb/lspd 2>&1; ls -la /data/adb/lspd/config 2>&1'"))
