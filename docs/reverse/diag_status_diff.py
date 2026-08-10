#!/usr/bin/env python3
"""对比 env/status 与 bluetooth/status 的 ble 数据。"""
import subprocess
import json


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


env = sh("curl -s http://127.0.0.1:18790/api/env/status")
bt = sh("curl -s http://127.0.0.1:18790/api/bluetooth/status")

print("=== env/status ble ===")
try:
    e = json.loads(env)
    print(json.dumps(e.get("data", {}).get("ble", {}), ensure_ascii=False)[:500])
except Exception as ex:
    print("parse err", ex, env[:200])

print("\n=== bluetooth/status ===")
try:
    b = json.loads(bt)
    print(json.dumps(b.get("data", {}), ensure_ascii=False)[:500])
except Exception as ex:
    print("parse err", ex, bt[:200])

print("\n=== env/status 全量 keys ===")
try:
    e = json.loads(env)
    print(list(e.get("data", {}).keys()))
except Exception:
    pass
