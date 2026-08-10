#!/usr/bin/env python3
"""实时捕获 ZVirtualEnv（system_server + 蓝牙进程），触发 WiFi+BLE 请求。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


sh("logcat -c")
proc = subprocess.Popen(
    ["adb", "logcat", "-v", "time", "-s", "ZVirtualEnv:*"],
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    errors="replace",
)
time.sleep(1)
sh("am start -a android.settings.BLUETOOTH_SETTINGS 2>/dev/null || true")
sh("am start -a android.settings.WIFI_SETTINGS 2>/dev/null || true")
time.sleep(10)
proc.terminate()
lines = []
try:
    while True:
        line = proc.stdout.readline()
        if not line:
            break
        lines.append(line.strip())
except Exception:
    pass
print("captured:", len(lines))
print("\n".join(lines[-40:]) if lines else "(none)")
