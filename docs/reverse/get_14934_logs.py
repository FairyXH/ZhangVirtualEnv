#!/usr/bin/env python3
"""抓检测器进程 14934 的 BLE/GNSS 注入日志。"""
import subprocess

p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
lines = out.splitlines()
for line in lines:
    parts = line.split()
    if len(parts) < 3:
        continue
    pid = parts[2]
    if pid == "14934" and "ZVirtualEnv" in line:
        if any(k in line for k in [
            "startScan", "virtual ble", "deliver virtual ble", "gnss injector",
            "registerGnssStatusCallback", "gnss virtual deliver", "env refresh",
            "sensor injector", "CellIdentityLte", "getAllCellInfo", "getScanResults",
            "findCallbackArg", "EnvCache", "refresh env",
        ]):
            print(line[:220])
print("---done---")
