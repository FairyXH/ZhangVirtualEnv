#!/usr/bin/env python3
"""解析 UI dump 中可点击的按钮并执行采集。"""
import subprocess
import re
import time


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 按钮列表 ===")
xml = sh("cat /sdcard/ui.xml")
for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*class="([^"]*)"[^>]*clickable="true"[^>]*bounds="(\[[^"]+\])"', xml):
    text, cls, bounds = m.groups()
    print(f"text={text!r} class={cls} bounds={bounds}")

print("\n=== 点击 '一键采集' 类按钮 ===")
# 找包含 采集 的按钮
targets = [m for m in re.finditer(r'<node[^>]*text="([^"]*采集[^"]*)"[^>]*bounds="(\[[^"]+\])"', xml)]
if targets:
    for m in targets:
        print("found:", m.group(1), m.group(2))
    # 取第一个可点击的
    bounds = targets[0].group(2)
    nums = re.findall(r"\d+", bounds)
    x = (int(nums[0]) + int(nums[2])) // 2
    y = (int(nums[1]) + int(nums[3])) // 2
    print(f"tapping {x},{y}")
    sh(f"input tap {x} {y}")
    time.sleep(8)
else:
    print("no 采集 button found")
