#!/usr/bin/env python3
"""检查 App 版本与完整 UI dump（含徽标与原始数据）。"""
import subprocess
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. App 版本 ===")
print(sh("dumpsys package io.github.fairyxh.VirtualEnv | grep -E 'versionName|lastUpdateTime' | head -3"))

print("\n=== 2. App 日志（env test） ===")
app = ""
for line in sh("ps -A | grep fairyxh.VirtualEnv").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        app = parts[1]
print("app_pid=", app)
if app:
    print(sh(f"logcat -d --pid={app} -t 300 | grep -iE 'env test|SettingsFragment|ZVirtualEnv' | tail -20") or "(none)")

print("\n=== 3. 完整 UI（含状态徽标） ===")
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*bounds="(\[[^\"]+\])"[^>]*/?>', xml):
    t = m.group(1)
    if t.strip():
        print(f"[{m.group(2)}] {t}")
