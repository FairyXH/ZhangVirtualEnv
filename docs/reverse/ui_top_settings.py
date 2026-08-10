#!/usr/bin/env python3
"""滚动到顶部查看 开始/结束 按钮 + 位置栏；测试结束停止。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


sh("input swipe 632 500 632 2400 500")
time.sleep(2)
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
for m in re.finditer(r'text="([^"]{0,300})"[^>]*bounds="(\[[^\"]+\])"', xml):
    t = m.group(1)
    if t.strip():
        print(f"[{m.group(2)}] {t}")
