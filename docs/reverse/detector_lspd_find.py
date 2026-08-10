#!/usr/bin/env python3
"""定位 LSPosed 实际安装与配置路径。"""
import subprocess

def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()

print("=== /data/adb ===")
print(sh("adb shell su -c 'ls -la /data/adb/ 2>/dev/null'"))
print("=== /data/adb/lspd ===")
print(sh("adb shell su -c 'ls -la /data/adb/lspd/ 2>/dev/null'"))
print("=== /data/adb/lspd/config ===")
print(sh("adb shell su -c 'ls -laR /data/adb/lspd/config/ 2>/dev/null | head -60'"))
print("=== lspd process ===")
print(sh("adb shell ps -A 2>/dev/null | grep -i lsp"))
print("=== zygisk ===")
print(sh("adb shell su -c 'ls -la /data/adb/modules/ 2>/dev/null'"))
