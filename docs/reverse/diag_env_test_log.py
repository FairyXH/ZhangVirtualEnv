#!/usr/bin/env python3
"""grep env test 日志与权限。"""
import subprocess


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


app = ""
for line in sh("ps -A | grep fairyxh.VirtualEnv").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        app = parts[1]
print("app_pid=", app)
if app:
    print("--- env test ---")
    print(sh(f"logcat -d --pid={app} | grep -iE 'env test|Settings|permission|denied' | tail -30") or "(none)")
print("--- permissions ---")
print(sh("dumpsys package io.github.fairyxh.VirtualEnv | grep -A 3 'BLUETOOTH_SCAN\|ACCESS_FINE_LOCATION\|READ_PHONE_STATE\|NEARBY_WIFI' | head -20"))
