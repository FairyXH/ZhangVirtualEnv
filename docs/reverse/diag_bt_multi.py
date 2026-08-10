#!/usr/bin/env python3
"""列出所有 com.android.bluetooth 进程并检查各自日志。"""
import subprocess


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 所有 bluetooth 进程 ===")
print(sh("ps -A | grep -E 'bluetooth'"))

print("\n=== 各 PID 的 ZVirtualEnv ===")
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2:
        pid = parts[1]
        out = sh(f"logcat -d --pid={pid} | grep ZVirtualEnv | tail -5")
        print(f"PID {pid}: {out if out else '(no ZVirtualEnv)'}")

print("\n=== lspd log 中两个进程的模块加载记录 ===")
print(sh("su -c 'grep -a \"com.android.bluetooth\" /data/adb/lspd/log/modules_*.log | grep -i virtualenv | tail -10'"))
