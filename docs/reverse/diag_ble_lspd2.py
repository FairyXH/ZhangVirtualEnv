#!/usr/bin/env python3
"""查看 LSPosed modules log 中关于 VirtualEnv/bluetooth 的加载记录。"""
import subprocess


def sh(cmd: str) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=60)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== modules log: VirtualEnv ===")
print(sh("su -c 'grep -a -i \"virtualenv\\|fairyxh\" /data/adb/lspd/log/modules_*.log | tail -60'"))

print("\n=== modules log: bluetooth ===")
print(sh("su -c 'grep -a -i bluetooth /data/adb/lspd/log/modules_*.log | tail -60'"))

print("\n=== verbose log: bluetooth ===")
print(sh("su -c 'grep -a -i bluetooth /data/adb/lspd/log/verbose_*.log | tail -60'"))

print("\n=== verbose log: virtualenv ===")
print(sh("su -c 'grep -a -i \"virtualenv\\|fairyxh\" /data/adb/lspd/log/verbose_*.log | tail -40'"))
