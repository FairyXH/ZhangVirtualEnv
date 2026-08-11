import subprocess, re, time

def sh(*args):
    return subprocess.run(args, capture_output=True, text=True).stdout

serial = "3B6F6JE910B4WVXT"

def dump():
    sh("adb", "-s", serial, "shell", "uiautomator dump /sdcard/ui.xml")
    return sh("adb", "-s", serial, "shell", "cat /sdcard/ui.xml")

def bounds_of(xml, text):
    m = re.search(r'text="' + text + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        m = re.search(r'[^>]*text="' + text + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        return None
    return (int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4)))

xml = dump()
b = bounds_of(xml, "显示悬浮窗")
print("float btn bounds:", b)
if b:
    cx = (b[0] + b[2]) // 2
    cy = (b[1] + b[3]) // 2
    sh("adb", "-s", serial, "shell", f"input tap {cx} {cy}")
    time.sleep(2)
    # overlay windows
    out = sh("adb", "-s", serial, "shell", "dumpsys window windows | grep -E 'Window #|mCurrentFocus|TYPE_APPLICATION_OVERLAY' | head -40")
    print(out)
