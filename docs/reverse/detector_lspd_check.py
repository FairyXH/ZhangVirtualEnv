#!/usr/bin/env python3
"""检查 LSPosed scope 数据库与检测器进程模块加载状态。"""
import subprocess

def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()

print("=== modules_config.db scope ===")
print(sh("adb shell su -c 'sqlite3 /data/adb/lspd/config/modules_config.db \"SELECT module_pkg_name, user_id, enabled, apk_path FROM modules ORDER BY module_pkg_name;\"' 2>&1"))
print("=== scope rows ===")
print(sh("adb shell su -c 'sqlite3 /data/adb/lspd/config/modules_config.db \".tables\"' 2>&1"))
print(sh("adb shell su -c 'sqlite3 /data/adb/lspd/config/modules_config.db \"SELECT * FROM scope;\"' 2>&1 | head -50"))
print("=== lspd log recent ===")
print(sh("adb shell su -c 'ls -la /data/adb/lspd/log/ 2>/dev/null' 2>&1"))
