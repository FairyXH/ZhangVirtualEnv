#!/usr/bin/env python3
"""精确验证开始/结束：先滚动使按钮可见，再操作。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


def dump():
    sh("uiautomator dump /sdcard/ui.xml")
    return sh("cat /sdcard/ui.xml")


def find_btn(xml, label):
    m = re.search(rf'text="{label}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        return (int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2
    return None


# 确保设置页
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(6)
xml = dump()
if find_btn(xml, "开始") is None:
    m = re.search(r'text="设置"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        x = (int(m.group(1)) + int(m.group(3))) // 2
        y = (int(m.group(2)) + int(m.group(4))) // 2
        sh(f"input tap {x} {y}")
        time.sleep(3)
        xml = dump()

# 滚动使按钮可见
for _ in range(3):
    b = find_btn(xml, "开始")
    e = find_btn(xml, "结束")
    if b and e:
        break
    sh("input swipe 632 2200 632 800 400")
    time.sleep(1)
    xml = dump()

b = find_btn(xml, "开始")
e = find_btn(xml, "结束")
print("开始 btn:", b, "结束 btn:", e)
if not b or not e:
    print("按钮不可见，退出")
    exit(1)

print("\n=== 1. 点击开始 ===")
sh(f"input tap {b[0]} {b[1]}")
time.sleep(4)
xml = dump()
# 检查按钮状态变化
print("开始 visible:", find_btn(xml, "开始") is not None)
print("结束 visible:", find_btn(xml, "结束") is not None)
# 数据区应显示 running/实时数据
print("has 测试中 or 数据:", bool(re.search(r'测试中|NR mcc|卫星|计步器|network:', xml)))

print("\n=== 2. 等待 4 秒再 dump（确认持续刷新） ===")
time.sleep(4)
xml = dump()
# 抓位置栏时间戳是否变化（实时刷新证据）
ts = re.findall(r'time=(\d+)', xml)
print("位置时间戳:", ts[:2])

print("\n=== 3. 点击结束 ===")
sh(f"input tap {e[0]} {e[1]}")
time.sleep(3)
xml = dump()
print("结束可见:", find_btn(xml, "结束") is not None)
print("开始可见:", find_btn(xml, "开始") is not None)
print("has 未开始:", "未开始" in xml)
