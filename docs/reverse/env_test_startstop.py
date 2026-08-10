#!/usr/bin/env python3
"""点击结束 → 验证停止；再点开始 → 验证重新启动。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


def dump():
    sh("uiautomator dump /sdcard/ui.xml")
    return sh("cat /sdcard/ui.xml")


print("=== 1. 点击结束 (885,1086) ===")
sh("input tap 885 1086")
time.sleep(3)
xml = dump()
m = re.search(r'text="结束"[^>]*enabled="true"', xml)
print("结束 still enabled:", bool(m))
# 检查 开始 是否重新可用
m2 = re.search(r'text="开始"[^>]*enabled="false"', xml)
print("开始 disabled:", bool(m2))
# 数据区是否显示未开始
print("has 未开始:", "未开始" in xml)

print("\n=== 2. 点击开始 (390,1086) ===")
sh("input tap 390 1086")
time.sleep(3)
xml = dump()
m2 = re.search(r'text="开始"[^>]*enabled="false"', xml)
print("开始 disabled:", bool(m2))
m = re.search(r'text="结束"[^>]*enabled="true"', xml)
print("结束 enabled:", bool(m))

print("\n=== 3. 等待实时刷新后再次 dump 数据 ===")
time.sleep(5)
xml = dump()
for label in ["位置", "基站", "蓝牙", "传感器", "GNSS"]:
    m = re.search(rf'text="{label}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        y = (int(m.group(2)) + int(m.group(4))) // 2
        vals = []
        for mm in re.finditer(r'text="([^"]{4,300})"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
            t = mm.group(1)
            yy = (int(mm.group(3)) + int(mm.group(5))) // 2
            if 0 < yy - y < 180 and t not in ["开始", "结束", "主页", "位置", "路线", "环境", "设置"]:
                vals.append(t)
        print(f"\n--- {label} ---")
        print("  " + "\n  ".join(vals[:4]) if vals else "  (no value)")
    else:
        print(f"\n--- {label} --- (not visible)")

print("\n=== 4. App 日志 ===")
app = ""
for line in sh("ps -A | grep fairyxh.VirtualEnv").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        app = parts[1]
if app:
    print(sh(f"logcat -d --pid={app} -t 200 | grep -iE 'env test' | tail -10") or "(no env test logs)")
