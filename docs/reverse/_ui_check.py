import subprocess

def sh(*args):
    return subprocess.run(args, capture_output=True, text=True).stdout

serial = "3B6F6JE910B4WVXT"
r = sh("adb", "-s", serial, "shell", "uiautomator dump /sdcard/ui.xml")
print("dump:", r.strip()[:200])
xml = sh("adb", "-s", serial, "shell", "cat /sdcard/ui.xml")
print("xml_len:", len(xml))
for kw in ["ZhangVirtualEnv", "模块状态", "功能状态", "悬浮窗", "一键采集", "已保存采集"]:
    print(kw, "->", kw in xml)
# count text attributes to see if page rendered
import re
texts = re.findall(r'text="([^"]+)"', xml)
print("texts:", texts[:40])
