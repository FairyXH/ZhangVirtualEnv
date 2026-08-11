import subprocess, re, time

def sh(*args):
    return subprocess.run(args, capture_output=True, text=True).stdout

serial = "3B6F6JE910B4WVXT"

def dump():
    sh("adb", "-s", serial, "shell", "uiautomator dump /sdcard/ui.xml")
    return sh("adb", "-s", serial, "shell", "cat /sdcard/ui.xml")

def tap_text(xml, text):
    m = re.search(r'text="' + text + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        return False
    cx = (int(m.group(1)) + int(m.group(3))) // 2
    cy = (int(m.group(2)) + int(m.group(4))) // 2
    sh("adb", "-s", serial, "shell", f"input tap {cx} {cy}")
    return True

xml = dump()
# go to env tab
tap_text(xml, "环境")
time.sleep(2)
xml = dump()
texts = re.findall(r'text="([^"]+)"', xml)
print("env page texts:", texts[:60])
