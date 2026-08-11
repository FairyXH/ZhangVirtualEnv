import subprocess, re, time

def sh(*args):
    return subprocess.run(args, capture_output=True, text=True).stdout

serial = "3B6F6JE910B4WVXT"

def dump():
    sh("adb", "-s", serial, "shell", "uiautomator dump /sdcard/ui.xml")
    return sh("adb", "-s", serial, "shell", "cat /sdcard/ui.xml")

def tap_text(xml, text):
    m = re.search(r'text="' + re.escape(text) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        return False
    cx = (int(m.group(1)) + int(m.group(3))) // 2
    cy = (int(m.group(2)) + int(m.group(4))) // 2
    sh("adb", "-s", serial, "shell", f"input tap {cx} {cy}")
    return True

def tap_center(xml, text):
    m = re.search(r'text="' + re.escape(text) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        return False
    return (int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2

# start app
sh("adb", "-s", serial, "shell", "am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(5)
xml = dump()
print("== home ==")
print("feature status ok:", "功能状态" in xml and "实时刷新" in xml)

# location page collapse/expand test
print("nav location:", tap_text(xml, "位置"))
time.sleep(3)
xml = dump()
c = tap_center(xml, "▼")
print("collapse arrow at:", c)
if c:
    sh("adb", "-s", serial, "shell", f"input tap {c[0]} {c[1]}")
    time.sleep(2)
    xml = dump()
    print("collapsed -> search gone:", "搜索地址" not in xml, "| expand arrow visible:", "▲" in xml)
    c2 = tap_center(xml, "▲")
    if c2:
        sh("adb", "-s", serial, "shell", f"input tap {c2[0]} {c2[1]}")
        time.sleep(2)
        xml = dump()
        print("expanded -> search back:", "搜索地址" in xml, "| collapse arrow:", "▼" in xml)

# route page
xml = dump()
print("nav route:", tap_text(xml, "路线"))
time.sleep(3)
xml = dump()
print("route satellite:", "卫星图" in xml, "| route collapse arrow:", "▼" in xml)

# home -> float open
xml = dump()
print("nav home:", tap_text(xml, "主页"))
time.sleep(2)
xml = dump()
print("click 显示悬浮窗:", tap_text(xml, "显示悬浮窗"))
time.sleep(3)
out = sh("adb", "-s", serial, "shell", "dumpsys window windows | grep -E 'io.github.fairyxh.VirtualEnv,' | head -2")
m = re.search(r'frame=\[Rect\((\d+), (\d+) - (\d+), (\d+)\)\]', out)
print("float window frame:", m.groups() if m else "none", "| window lines:", len(out.splitlines()))
