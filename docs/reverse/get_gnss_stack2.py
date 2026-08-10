#!/usr/bin/env python3
"""抓最新 build virtual gnss status failed 的完整异常。"""
import subprocess

p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
lines = out.splitlines()
capture = False
count = 0
for line in lines:
    if "build virtual gnss status failed" in line:
        capture = True
        count = 0
        continue
    if capture and "ZVirtualEnv" in line:
        print(line[:250])
        count += 1
        if count > 8:
            capture = False
            print("---")
            break
