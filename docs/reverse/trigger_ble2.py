#!/usr/bin/env python3
"""触发 LE 扫描并实时观察蓝牙栈 Hook 投递日志。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


# 确保 BLE 数据已设置
subprocess.run(["adb", "forward", "tcp:18790", "tcp:18790"], capture_output=True, timeout=30)

proc = subprocess.Popen(
    ["adb", "logcat", "-v", "time", "-s", "ZVirtualEnv:*"],
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    errors="replace",
)
time.sleep(1)
print("triggering settings scan...")
sh("am start -a android.settings.BLUETOOTH_SETTINGS 2>/dev/null || true")
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
