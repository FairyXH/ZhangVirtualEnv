#!/usr/bin/env python3
"""检查蓝牙进程线程（ZVE-EnvCache）与 logcat 从头输出。"""
import subprocess


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 7309 进程线程列表 ===")
print(sh("su -c 'ls /proc/7309/task | wc -l'"))
print(sh("su -c 'for t in /proc/7309/task/*/comm; do cat $t 2>/dev/null; done | sort | uniq -c | sort -rn | head -30'"))

print("\n=== 2. logcat main buffer 中 7309 全部（ZVirtualEnv 可能被覆盖，看总量） ===")
print(sh("logcat -d | grep ' 7309 ' | wc -l"))
print(sh("logcat -d | grep ' 7309 ' | grep -iE 'ZVirtualEnv|EnvCache|rawGet' | tail -5") or "(no ZVirtualEnv)")

print("\n=== 3. 8350 进程线程 ===")
print(sh("su -c 'for t in /proc/8350/task/*/comm; do cat $t 2>/dev/null; done | sort | uniq -c | sort -rn | head -20'"))
print(sh("logcat -d | grep ' 8350 ' | grep -iE 'ZVirtualEnv|EnvCache' | tail -5") or "(no ZVirtualEnv)")
