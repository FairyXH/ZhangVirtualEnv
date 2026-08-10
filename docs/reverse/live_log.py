#!/usr/bin/env python3
"""实时监听 ZVirtualEnv 日志，同时触发 WiFi 请求观察。"""
import subprocess
import threading
import time


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


# 后台实时 logcat
proc = subprocess.Popen(
    ["adb", "logcat", "-v", "time", "-s", "ZVirtualEnv:*", "WifiService:*"],
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    errors="replace",
)

time.sleep(3)
print("triggering wifi request...")
print(sh("am start -a android.settings.WIFI_SETTINGS 2>/dev/null || true"))
time.sleep(6)

print("collecting logcat...")
lines = []
try:
    import select
    while True:
        line = proc.stdout.readline()
        if not line:
            break
        lines.append(line.strip())
        if len(lines) > 30:
            break
except Exception:
    pass
proc.terminate()
print("\n".join(lines) if lines else "(no ZVirtualEnv lines captured)")
