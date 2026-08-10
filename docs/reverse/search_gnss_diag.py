#!/usr/bin/env python3
"""搜索 findCallbackArg / gnss 相关失败日志。"""
import subprocess

p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
for line in out.splitlines():
    if any(k in line for k in [
        "findCallbackArg", "gnss register intercept", "gnss virtual deliver",
        "gnss injector started", "build virtual gnss status",
    ]):
        print(line[:220])
print("---done---")
