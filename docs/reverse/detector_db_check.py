#!/usr/bin/env python3
"""拉取 modules_config.db 检查模块与 scope。"""
import subprocess
import sqlite3
import os

LOCAL = r"D:\Files\Develop\Android\ZhangVirtualProject\ZhangVirtualEnv\docs\reverse\modules_config.db"

def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()

print("=== pull db ===")
print(sh("adb pull /data/adb/lspd/config/modules_config.db " + LOCAL))
print(sh("adb pull /data/adb/lspd/config/modules_config.db-wal " + LOCAL + "-wal"))

if not os.path.exists(LOCAL):
    print("pull failed")
    raise SystemExit(1)

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
