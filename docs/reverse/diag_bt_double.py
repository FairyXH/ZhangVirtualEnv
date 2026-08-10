#!/usr/bin/env python3
"""检查两个蓝牙进程的 Hook 状态、EnvCache 线程、BLE 缓存数据。"""
import subprocess


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 蓝牙进程列表 ===")
print(sh("ps -A | grep com.android.bluetooth"))

print("\n=== 2. 各进程模块加载（lspd） ===")
print(sh("su -c 'grep -a \"com.android.bluetooth\" /data/adb/lspd/log/modules_*.log | grep -i virtualenv | tail -6'"))

for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) < 2 or "com.android.bluetooth" not in line:
        continue
    pid = parts[1]
    print(f"\n=== PID {pid} ===")
    print("threads ZVE:", sh(f"su -c 'for t in /proc/{pid}/task/*/comm; do cat $t 2>/dev/null; done' | grep -i ZVE") or "(none)")
    print("logcat ZVirtualEnv:", sh(f"logcat -d --pid={pid} | grep ZVirtualEnv | tail -5") or "(none)")
    print("logcat hooked:", sh(f"logcat -d --pid={pid} | grep -iE 'hooked|class not found|installed' | tail -5") or "(none)")

print("\n=== 3. 当前 BLE 状态（backend API） ===")
print(sh("su -c 'curl -s -m 3 http://127.0.0.1:18790/api/env/status | head -c 600'"))
