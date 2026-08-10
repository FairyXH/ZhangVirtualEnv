#!/usr/bin/env python3
"""adb 执行助手 v2：bytes 输出，避免二进制 DB 解码错误。"""
import subprocess
import sys


def run(cmd: str) -> None:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=60)
    print(f"$ adb shell {cmd}")
    try:
        out = p.stdout.decode("utf-8", errors="replace")
        print(out)
    except Exception as e:
        print("OUT bytes:", p.stdout[:2000], "err:", e)
    if p.stderr:
        print("STDERR:", p.stderr.decode("utf-8", errors="replace"))


if __name__ == "__main__":
    for cmd in sys.argv[1:]:
        run(cmd)
