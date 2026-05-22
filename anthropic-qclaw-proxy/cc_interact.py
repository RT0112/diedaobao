#!/usr/bin/env python3
"""CC Interactive Mode Launcher - pexpect 简化版"""
import pexpect, sys, time, os

def main():
    print("🚀 Starting CC Interactive Mode...")
    child = pexpect.spawn('./cc-agent.sh --interactive', encoding='utf-8', timeout=15, dimensions=(30, 120))
    
    # 不设 logfile_read，避免 bytes/str 问题
    time.sleep(3)
    
    # Step 1: 主题选择（Dark mode 已预选）
    print("[1/4] Theme selection...")
    child.send('\r')
    time.sleep(2)
    
    # Step 2: 信任文件夹
    print("[2/4] Trust folder...")
    child.send('\r')
    time.sleep(2)
    
    # Step 3: Bypass Permissions - 用下箭头选择 "Yes, I accept"
    print("[3/4] Bypass Permissions → sending DOWN arrow...")
    # ESC [ B = ANSI 下箭头
    child.send('\x1b')
    time.sleep(0.1)
    child.send('[')
    time.sleep(0.1)
    child.send('B')
    time.sleep(1)
    
    print("[3/4] Bypass Permissions → sending ENTER...")
    child.send('\r')
    time.sleep(2)
    
    # Step 4: 安全提示（如果还有的话）
    print("[4/4] Final ENTER...")
    child.send('\r')
    time.sleep(3)
    
    # 检查是否成功
    print("Checking CC status...")
    try:
        idx = child.expect(['claude>', 'Human:', pexpect.TIMEOUT, pexpect.EOF], timeout=5)
        if idx <= 1:
            print("\n✅ CC Interactive Mode READY!")
            child.interact()
            return
    except:
        pass
    
    # 如果还没准备好，进入交互模式让用户手动操作
    print("\n⚠ Switching to manual mode...")
    try:
        child.interact()
    except:
        print("CC process ended")

if __name__ == '__main__':
    main()
