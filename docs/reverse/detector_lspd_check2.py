#!/usr/bin/env python3
"""检查 LSPosed 配置文件 + 检测器进程模块加载。"""
import subprocess
import re

def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()

print("=== LSPosed config files ===")
print(sh("adb shell su -c 'ls -la /data/adb/lspd/config/ 2>/dev/null'"))
print("=== modules.list ===")
print(sh("adb shell su -c 'cat /data/adb/lspd/config/modules.list 2>/dev/null'"))
print("=== scopes.list ===")
print(sh("adb shell su -c 'cat /data/adb/lspd/config/scopes.list 2>/dev/null'"))

print("=== logcat: detector pid 10439 module logs ===")
out = sh("adb logcat -d 2>/dev/null | grep -E 'ZVirtualEnv|libxposed|LSPosed' | grep -E '10439|VirEnvDetector' | tail -60")
print(out if out else "(no module logs for detector)")

print("=== logcat: all ZVirtualEnv Entry lines (recent) ===")
out = sh("adb logcat -d 2>/dev/null | grep -E '\\[Entry\\]' | tail -40")
print(out if out else "(no Entry lines)")
