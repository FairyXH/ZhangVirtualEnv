#!/usr/bin/env python3
"""确认 API 连通性 + 当前 system_server + 触发控制端 App 采集按钮。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. system_server PID ===")
print(sh("ps -A | grep system_server"))

print("\n=== 2. root shell curl 18790 ===")
print(sh("su -c 'curl -s -m 3 http://127.0.0.1:18790/api/status | head -c 200'") or "(curl failed)")

print("\n=== 3. 控制端 App 当前状态 ===")
print(sh("dumpsys activity top | grep -E 'ACTIVITY|fairyxh' | head -5"))

print("\n=== 4. 打开控制端 App 采集页 ===")
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity 2>&1 || true")
time.sleep(3)
print(sh("dumpsys activity top | grep -E 'ACTIVITY|fairyxh' | head -5"))
