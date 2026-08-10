#!/usr/bin/env python3
"""检查蓝牙进程 EnvCache 轮询状态 + 连接。"""
import subprocess


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 蓝牙进程列表 ===")
print(sh("ps -A | grep com.android.bluetooth"))

print("\n=== 2. logdaemon 中蓝牙进程全部 ZVirtualEnv（找 EnvCache 失败日志） ===")
print(sh("su -c 'grep -a \"(com.android.bluetooth)\" /data/adb/lspd/log/modules_*.log | grep -i -E \"EnvCache|rawGet|ble\" | tail -20'") or "(none)")

print("\n=== 3. 蓝牙进程能否连接 18790（nsenter 到进程网络命名空间） ===")
bt_pid = ""
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and "com.android.bluetooth" in line and parts[0] != "root":
        bt_pid = parts[1]
print("bt_pid=", bt_pid)
if bt_pid:
    print("--- 进程网络 namespace ---")
    print(sh(f"su -c 'nsenter -t {bt_pid} -n -- /system/bin/curl -s -m 2 http://127.0.0.1:18790/api/env/status 2>&1 | head -c 300'") or "(nsenter failed)")
    print("--- 进程 TCP 连接 18790 ---")
    print(sh(f"su -c 'ls -l /proc/{bt_pid}/fd 2>/dev/null | grep -c socket'"))

print("\n=== 4. 全局 logcat 蓝牙进程 ZVirtualEnv（含 EnvCache） ===")
print(sh("logcat -d | grep -E ' 7530 | 8350 ' | grep ZVirtualEnv | tail -10") or "(none)")
