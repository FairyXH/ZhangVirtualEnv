#!/usr/bin/env python3
"""检查蓝牙进程 EnvCache 轮询：TCP 连接 + rawGet 是否成功。"""
import subprocess


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 蓝牙进程 TCP 连接 ===")
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and "com.android.bluetooth" in line and parts[0] != "root":
        pid = parts[1]
        print(f"\nbt_pid={pid}:")
        print(sh(f"su -c 'cat /proc/{pid}/net/tcp6 | head -20'") or "(none)")
        print("--- sockets to 18790 ---")
        print(sh(f"su -c 'cat /proc/{pid}/net/tcp6' | grep -iE ':496E' | head -5") or "(none)")

print("\n=== 2. 蓝牙进程 ZVE-EnvCache 线程存在？ ===")
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and "com.android.bluetooth" in line:
        pid = parts[1]
        print(f"PID {pid}:", sh(f"su -c 'for t in /proc/{pid}/task/*/comm; do cat $t 2>/dev/null; done' | grep -i ZVE") or "(none)")

print("\n=== 3. 蓝牙进程能否 curl 18790 ===")
bt_pid = ""
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and "com.android.bluetooth" in line and parts[0] != "root":
        bt_pid = parts[1]
if bt_pid:
    # 尝试在蓝牙进程 context 下 curl（可能需要 nsenter）
    print(sh(f"su -c 'nsenter -t {bt_pid} -n -- curl -s -m 2 http://127.0.0.1:18790/api/env/status 2>&1 | head -c 200'") or "(nsenter failed)")
