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

xml = dump()
print("nav to location:", tap_text(xml, "位置"))
time.sleep(3)
xml = dump()
texts = re.findall(r'text="([^"]+)"', xml)
print("location page texts:", texts[:60])
# click satellite toggle
print("click satellite:", tap_text(xml, "卫星图"))
time.sleep(2)
xml = dump()
texts2 = re.findall(r'text="([^"]+)"', xml)
print("after satellite click:", [t for t in texts2 if "图" in t or "▼" in t or "▲" in t][:10])
# click collapse arrow ▼
m = re.search(r'text="▼"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    cx = (int(m.group(1)) + int(m.group(3))) // 2
    cy = (int(m.group(2)) + int(m.group(4))) // 2
    sh("adb", "-s", serial, "shell", f"input tap {cx} {cy}")
    time.sleep(2)
    xml2 = dump()
    texts3 = re.findall(r'text="([^"]+)"', xml2)
    print("after collapse:", [t for t in texts3 if "▲" in t or "▼" in t][:5], "search gone:", "搜索地址" not in xml2)
else:
    print("collapse arrow not found")
