#!/usr/bin/env python3
"""查看蓝牙进程 14:54:31 前后完整日志（Java 层扫描链路）。"""
import subprocess


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


bt = "7489"
print(sh(f"logcat -d --pid={bt} -t 400 | grep -E '14:5[0-9]:' | grep -iE 'Transitional|ScanController|GattService|scan|register|clientIf' | head -60"))
