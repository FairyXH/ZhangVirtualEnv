#!/usr/bin/env python3
"""检查最新 native crash（14:26 reboot 后）内容。"""
import subprocess


def sh(cmd: str) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=60)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 14:26 native crash 全文 ===")
print(sh("su -c 'cat /data/system/dropbox/system_app_native_crash@1786343195862.txt'"))
