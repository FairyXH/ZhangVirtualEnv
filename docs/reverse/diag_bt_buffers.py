#!/usr/bin/env python3
"""检查蓝牙进程 ZLog 是否进入任意 logcat buffer；实时捕获。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


pid = ""
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        pid = parts[1]
print("bt_pid=", pid)

print("\n=== 1. 所有 buffer 中 ZVirtualEnv ===")
for buf in ["main", "system", "events", "crash", "radio"]:
    out = sh(f"logcat -d -b {buf} | grep -E 'ZVirtualEnv' | tail -3")
    print(f"[{buf}] {'OK' if out else '(none)'}")

print("\n=== 2. 实时捕获 8 秒（含蓝牙进程轮询应产生 rawGet failed） ===")
proc = subprocess.Popen(
    ["adb", "logcat", "-v", "time", "-s", "ZVirtualEnv:*"],
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    errors="replace",
)
time.sleep(8)
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
print("\n".join(lines[-20:]) if lines else "(no ZVirtualEnv captured)")
