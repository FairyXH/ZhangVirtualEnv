#!/usr/bin/env python3
"""完整检查两个蓝牙进程的线程与模块加载。"""
import subprocess


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


for pid in ["8350", "7309"]:
    print(f"\n=== PID {pid} 完整线程（grep ZVE/EnvCache） ===")
    print(sh(f"su -c 'for t in /proc/{pid}/task/*/comm; do cat $t 2>/dev/null; done' | grep -iE 'ZVE|EnvCache|ZVirtual'") or "(no ZVE thread)")
    print(f"=== PID {pid} maps 模块 dex ===")
    print(sh(f"su -c 'grep -iE \"fairyxh|VirtualEnv|base.apk\" /proc/{pid}/maps | head -3'") or "(no module dex)")
    print(f"=== PID {pid} cmdline ===")
    print(sh(f"cat /proc/{pid}/cmdline | tr '\\0' ' '"))

print("\n=== lspd log 完整（含线程号上下文） ===")
print(sh("su -c 'grep -a \"ble stack hooks\" /data/adb/lspd/log/modules_*.log | tail -3'"))
print(sh("su -c 'grep -a \"onModuleLoaded process=com.android.bluetooth\" /data/adb/lspd/log/modules_*.log | tail -3'"))
