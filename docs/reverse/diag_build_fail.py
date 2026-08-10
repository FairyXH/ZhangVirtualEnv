#!/usr/bin/env python3
"""查看最新 logdaemon 投递链（build 失败原因）。"""
import subprocess


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print(sh("su -c 'grep -a \"ble stack\\|build scan\\|build virtual\" /data/adb/lspd/log/modules_*.log | tail -20'") or "(none)")
