#!/usr/bin/env python3
"""检查当前 UI 与 App 日志。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 前台 Activity ===")
print(sh("dumpsys activity top | grep -E 'ACTIVITY' | head -4"))

print("\n=== 2. App 进程 ===")
app = ""
for line in sh("ps -A | grep fairyxh.VirtualEnv").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        app = parts[1]
print("app_pid=", app)

print("\n=== 3. App 最近日志 ===")
if app:
    print(sh(f"logcat -d --pid={app} -t 200 | tail -40"))

print("\n=== 4. UI dump 状态 ===")
sh("uiautomator dump /sdcard/ui3.xml")
xml = sh("cat /sdcard/ui3.xml")
for m in re.finditer(r'text="([^"]{0,35})"[^>]*clickable="(true|false)"[^>]*bounds="(\[[^"]+\])"', xml):
    print("text:", m.group(1), "clickable:", m.group(2), m.group(3))
