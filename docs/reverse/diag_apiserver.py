#!/usr/bin/env python3
"""检查 18790 端口监听与 ApiServer 状态。"""
import subprocess


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 18790 端口监听 ===")
print(sh("su -c 'ss -tlnp | grep 18790'") or "(no listener)")
print(sh("netstat -tlnp 2>/dev/null | grep 18790") or "(netstat no listener)")

print("\n=== 2. system_server ApiServer 日志 ===")
print(sh("logcat -d --pid=4918 | grep -iE 'ApiServer|api server|18790|Backend' | tail -20") or "(none)")

print("\n=== 3. system_server 最近异常 ===")
print(sh("logcat -d --pid=4918 | grep -iE 'FATAL|Exception|Error' | tail -10"))
