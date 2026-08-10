#!/usr/bin/env python3
"""检查各进程 ZVirtualEnv 日志状态。"""
import subprocess


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== system_server (4918) ZVirtualEnv ===")
print(sh("logcat -d --pid=4918 | grep -iE 'ZVirtualEnv|WifiService|Hook' | tail -30"))

print("\n=== system_server 完整 hook 安装日志 ===")
print(sh("logcat -d --pid=4918 | grep -iE 'hooked|installed|Backend|ApiServer|class not found' | tail -30"))

print("\n=== lspd 最新 modules log（找 VirtualEnv hook 细节） ===")
print(sh("su -c 'grep -a -iE \"virtualenv.*(hooked|not found|installed|failed)\" /data/adb/lspd/log/modules_*.log | tail -30'"))
