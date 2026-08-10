#!/usr/bin/env python3
"""稳定版：确认页面 → 设置 tab → 滚动 → 开始 → 读报告。"""
import subprocess
import time
import re
import json


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


def curl(method: str, path: str, body: str = "") -> str:
    if body:
        cmd = f'curl -s -X {method} -H "Content-Type: application/json" -d \'{body}\' http://127.0.0.1:18790{path}'
    else:
        cmd = f'curl -s -X {method} http://127.0.0.1:18790{path}'
    return sh(cmd)


def dump():
    sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
    return sh("cat /sdcard/ui.xml")


def find(xml, label):
    m = re.search(rf'text="{label}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        return (int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2
    return None


print("=== 1. 启动 App ===")
sh("am force-stop io.github.fairyxh.VirtualEnv")
time.sleep(2)
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(10)
xml = dump()
print("主页可见:", "开始采集" in xml or "主页" in xml)
print("有设置 tab:", find(xml, "设置") is not None)

print("\n=== 2. 设置 tab ===")
p = find(xml, "设置")
if p:
    sh(f"input tap {p[0]} {p[1]}")
    time.sleep(5)
    xml = dump()
    print("设置页标题:", "环境实时测试" in xml)

print("\n=== 3. 滚动找开始按钮 ===")
for i in range(6):
    p = find(xml, "开始")
    if p and "结束" in xml:
        print(f"找到开始 {p}")
        break
    sh("input swipe 632 2200 632 800 400")
    time.sleep(1)
    xml = dump()
else:
    print("未找到开始按钮")
    # 打印当前可见文本帮助诊断
    for m in re.finditer(r'<node[^>]*text="([^"]{0,30})"', xml):
        if m.group(1).strip():
            print("  ", m.group(1))
    exit(1)

print("\n=== 4. 点击开始 ===")
sh(f"input tap {p[0]} {p[1]}")
time.sleep(3)
xml = dump()
print("点击后 开始 disabled:", bool(re.search(r'text="开始"[^>]*enabled="false"', xml)))
print("点击后 结束 enabled:", bool(re.search(r'text="结束"[^>]*enabled="true"', xml)))

time.sleep(12)

print("\n=== 5. 读报告 ===")
report = curl("GET", "/api/test/report")
try:
    j = json.loads(report)
    print(json.dumps(j, ensure_ascii=False, indent=1)[:2600])
except Exception:
    print(report[:300])

print("\n=== 6. App 日志 ===")
app = ""
for line in sh("ps -A | grep fairyxh.VirtualEnv").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        app = parts[1]
print("pid:", app)
if app:
    print(sh(f"logcat -d --pid={app} -t 250 | grep -iE 'env test|framework env|sensor injector|gnss|ZVirtualEnv' | tail -15") or "(none)")
