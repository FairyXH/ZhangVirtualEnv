#!/usr/bin/env python3
"""adb pull 需要 su 权限：用 su cat 重定向到 /sdcard 再 pull。"""
import subprocess
import os
import shutil

LOCAL = r"D:\Files\Develop\Android\ZhangVirtualProject\ZhangVirtualEnv\docs\reverse\modules_config.db"

def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()

print("=== copy to /sdcard with su ===")
print(sh("adb shell su -c 'cp /data/adb/lspd/config/modules_config.db /sdcard/modules_config.db && chmod 644 /sdcard/modules_config.db'"))
print(sh("adb shell su -c 'cp /data/adb/lspd/config/modules_config.db-wal /sdcard/modules_config.db-wal && chmod 644 /sdcard/modules_config.db-wal'"))
print("=== pull ===")
print(sh("adb pull /sdcard/modules_config.db " + LOCAL))
print(sh("adb pull /sdcard/modules_config.db-wal " + LOCAL + "-wal"))

if not os.path.exists(LOCAL):
    print("pull failed")
    raise SystemExit(1)

import sqlite3
conn = sqlite3.connect(LOCAL)
cur = conn.cursor()
print("=== tables ===")
for row in cur.execute("SELECT name FROM sqlite_master WHERE type='table'"):
    print(row)

print("=== modules ===")
try:
    for row in cur.execute("SELECT * FROM modules"):
        print(row)
except Exception as e:
    print("modules err:", e)

print("=== scope ===")
try:
    for row in cur.execute("SELECT * FROM scope"):
        print(row)
except Exception as e:
    print("scope err:", e)

conn.close()
