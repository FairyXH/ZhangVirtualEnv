#!/usr/bin/env python3
"""确认 su 与 LSPosed 环境。"""
import subprocess

def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()

print("=== su test ===")
print(sh("adb shell su -c id 2>&1"))
print("=== whoami ===")
print(sh("adb shell whoami 2>&1"))
print("=== lspd processes ===")
print(sh("adb shell ps -A | grep -iE 'lsp|zygisk|daemon' 2>&1"))
print("=== pm list packages lsposed ===")
print(sh("adb shell pm list packages | grep -iE 'lsp|virtualenv|virenv' 2>&1"))
print("=== logcat LSPosed (last 30) ===")
print(sh("adb logcat -d | grep -iE 'lsposed|LSPosed|zygisk' | tail -30"))
