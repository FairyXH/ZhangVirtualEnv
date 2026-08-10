#!/usr/bin/env python3
"""抓 build virtual gnss status 完整堆栈。"""
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
        print(line[:220])
        continue
    if capture:
        print(line[:220])
        count += 1
        if count > 12 or "at " not in line:
            capture = False
print("---done---")
