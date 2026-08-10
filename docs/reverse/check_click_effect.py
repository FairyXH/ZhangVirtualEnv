#!/usr/bin/env python3
"""查看点击后的完整日志，确认采集是否触发。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 当前前台 ===")
print(sh("dumpsys activity top | grep -E 'ACTIVITY' | head -3"))

print("\n=== App 进程最近日志 ===")
app_pid = ""
for line in sh("ps -A | grep fairyxh.VirtualEnv").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        app_pid = parts[1]
print("app_pid=", app_pid)
if app_pid:
    print(sh(f"logcat -d --pid={app_pid} -t 100 | tail -40"))
