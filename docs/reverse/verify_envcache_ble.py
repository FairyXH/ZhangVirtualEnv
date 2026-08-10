#!/usr/bin/env python3
"""验证蓝牙进程 EnvCache 轮询在拉取 BLE 数据（TCP 连接频率 + 后端状态）。"""
import subprocess
import time
import json


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 确认 BLE 已启用（后端） ===")
status = sh("curl -s http://127.0.0.1:18790/api/env/status")
print(status[:300])

print("\n=== 2. 蓝牙进程 EnvCache 轮询活动（采样 6 秒 TCP 连接） ===")
bt = ""
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and "com.android.bluetooth" in line and parts[0] != "root":
        bt = parts[1]
print("bt_pid=", bt)
if bt:
    for i in range(6):
        conns = sh(f"su -c 'cat /proc/{bt}/net/tcp6' | grep -ciE ':496E'")
        now = time.strftime("%H:%M:%S")
        print(f"{now}: tcp6 entries to 18790 = {conns}")
        time.sleep(1)

print("\n=== 3. EnvCache 线程存在 ===")
if bt:
    print(sh(f"su -c 'for t in /proc/{bt}/task/*/comm; do cat $t 2>/dev/null; done' | grep -i ZVE") or "(none)")

print("\n=== 4. 后端 BLE 数据在（供 EnvCache 拉取） ===")
print(sh("curl -s http://127.0.0.1:18790/api/bluetooth/status")[:300])
