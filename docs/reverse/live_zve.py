#!/usr/bin/env python3
"""实时捕获 ZVirtualEnv 日志 + 触发扫描，验证 Hook 安装与投递。"""
import subprocess
import threading
import time


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


proc = subprocess.Popen(
    ["adb", "logcat", "-v", "time", "-s", "ZVirtualEnv:*"],
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    errors="replace",
)
time.sleep(2)
print("triggering BLE scan...")
sh("am start -a android.settings.BLUETOOTH_SETTINGS 2>/dev/null || true")
time.sleep(8)

lines = []
proc.terminate()
# 读取已缓冲的输出
try:
    while True:
        line = proc.stdout.readline()
        if not line:
            break
        lines.append(line.strip())
except Exception:
    pass
print("captured lines:", len(lines))
print("\n".join(lines[:60]) if lines else "(no ZVirtualEnv captured)")
