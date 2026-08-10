#!/usr/bin/env python3
"""确认最新检测器进程的 gnss 判定。"""
import subprocess
import time

time.sleep(20)
p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
verdicts = [l for l in out.splitlines() if "VirEnvDetector" in l and any(k in l for k in ["location:", "cell:", "ble:", "wifi:", "sensor:", "gnss:"])]
for line in verdicts[-6:]:
    print(line[:170])
print("---gnss deliver logs---")
seen = set()
for line in out.splitlines():
    if "gnss" in line.lower() and "ZVirtualEnv" in line and any(k in line for k in ["injector", "deliver", "virtual gnss", "build virtual"]):
        key = line[:130]
        if key not in seen:
            seen.add(key)
            print(line[:220])
