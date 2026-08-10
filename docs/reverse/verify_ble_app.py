#!/usr/bin/env python3
"""用控制端 App 触发 LE 扫描验证虚拟 BLE 投递。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 蓝牙进程 EnvCache 轮询日志 ===")
pid = ""
for line in sh("ps -A | grep com.android.bluetooth").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        pid = parts[1]
print("bt_pid=", pid)
if pid:
    print(sh(f"logcat -d --pid={pid} | grep -iE 'EnvCache|rawGet|refresh' | tail -10") or "(no cache logs)")

print("\n=== 2. 启动控制端 App ===")
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity 2>/dev/null || am start -n io.github.fairyxh.VirtualEnv/.MainActivity 2>/dev/null || true")
time.sleep(5)

print("=== 3. 控制端 App 进程 ZVirtualEnv ===")
app_pid = ""
for line in sh("ps -A | grep fairyxh.VirtualEnv").splitlines():
    parts = line.split()
    if len(parts) >= 2:
        app_pid = parts[1]
print("app_pid=", app_pid)
if app_pid:
    print(sh(f"logcat -d --pid={app_pid} | grep -iE 'ZVirtualEnv|ble|startScan' | tail -20"))

print("\n=== 4. 蓝牙进程全部 ZVirtualEnv ===")
if pid:
    print(sh(f"logcat -d --pid={pid} | grep -iE 'ZVirtualEnv' | tail -20") or "(no ZVirtualEnv in bt)")
