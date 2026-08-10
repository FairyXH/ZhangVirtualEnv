#!/usr/bin/env python3
"""用 uiautomator 点击控制端 App 的采集按钮，触发 LE 扫描验证 BLE Hook。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 启动控制端 App ===")
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(4)

print("=== 2. UI dump ===")
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
print(xml[:3000] if xml else "(no xml)")
