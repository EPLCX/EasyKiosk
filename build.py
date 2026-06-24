#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Kiosk 广告机 - 编译工具
交互式编译脚本，支持 Debug / Release 构建、安装、清理
"""

import os
import sys
import subprocess
import shutil

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
GRADLE = os.path.join(PROJECT_DIR, "gradle-8.5", "bin", "gradle")
APK_DEBUG_DIR = os.path.join(PROJECT_DIR, "app", "build", "outputs", "apk", "debug")
APK_RELEASE_DIR = os.path.join(PROJECT_DIR, "app", "build", "outputs", "apk", "release")
# ABI 拆分已开启 (arm64-v8a, armeabi-v7a)，没有 universal APK
APK_DEBUG = os.path.join(APK_DEBUG_DIR, "app-arm64-v8a-debug.apk")
APK_RELEASE = os.path.join(APK_RELEASE_DIR, "app-arm64-v8a-release.apk")


def print_header(title):
    """打印带颜色的标题"""
    print(f"\n  {'=' * 42}")
    print(f"    {title}")
    print(f"  {'=' * 42}\n")


def run_gradle(*tasks):
    """执行 Gradle 命令"""
    cmd = [GRADLE] + list(tasks) + ["--no-daemon"]
    print(f"  运行: {' '.join(cmd)}\n")
    result = subprocess.run(cmd, cwd=PROJECT_DIR, shell=True)
    return result.returncode == 0


def find_apk(directory, abi="arm64-v8a"):
    """查找 APK 文件，优先匹配指定 ABI"""
    if not os.path.isdir(directory):
        return None
    for f in os.listdir(directory):
        if f.endswith(".apk") and abi in f:
            return os.path.join(directory, f)
    for f in os.listdir(directory):
        if f.endswith(".apk"):
            return os.path.join(directory, f)
    return None


def build_debug():
    print_header("编译 Debug 版本")
    if run_gradle("assembleDebug"):
        apk = find_apk(APK_DEBUG_DIR)
        print(f"\n  [OK] Debug 编译成功！")
        print(f"  APK: {apk or '未找到 APK'}")
    else:
        print(f"\n  [!!] 编译失败，请检查错误信息")
    input("\n  按回车键返回菜单...")


def build_release():
    print_header("编译 Release 版本")
    if run_gradle("assembleRelease"):
        apk = find_apk(APK_RELEASE_DIR)
        print(f"\n  [OK] Release 编译成功！")
        print(f"  APK: {apk or '未找到 APK'}")
    else:
        print(f"\n  [!!] 编译失败，请检查错误信息")
    input("\n  按回车键返回菜单...")


def clean_build_debug():
    print_header("清理并编译 Debug 版本")
    if run_gradle("clean", "assembleDebug"):
        apk = find_apk(APK_DEBUG_DIR)
        print(f"\n  [OK] 清理编译成功！")
        print(f"  APK: {apk or '未找到 APK'}")
    else:
        print(f"\n  [!!] 编译失败，请检查错误信息")
    input("\n  按回车键返回菜单...")


def clean_build_release():
    print_header("清理并编译 Release 版本")
    if run_gradle("clean", "assembleRelease"):
        apk = find_apk(APK_RELEASE_DIR)
        print(f"\n  [OK] 清理编译成功！")
        print(f"  APK: {apk or '未找到 APK'}")
    else:
        print(f"\n  [!!] 编译失败，请检查错误信息")
    input("\n  按回车键返回菜单...")


def show_output():
    print_header("编译输出路径")
    print(f"  Debug APK 目录:")
    print(f"    {APK_DEBUG_DIR}")
    print()
    print(f"  Release APK 目录:")
    print(f"    {APK_RELEASE_DIR}")
    print()
    print(f"  检查 APK 文件...")
    print()
    debug_apk = find_apk(APK_DEBUG_DIR)
    release_apk = find_apk(APK_RELEASE_DIR)
    if debug_apk:
        size = os.path.getsize(debug_apk) // 1024
        print(f"  [OK] Debug APK: {os.path.basename(debug_apk)} ({size} KB)")
    else:
        print(f"  [..] Debug APK 未编译")
    if release_apk:
        size = os.path.getsize(release_apk) // 1024
        print(f"  [OK] Release APK: {os.path.basename(release_apk)} ({size} KB)")
    else:
        print(f"  [..] Release APK 未编译")
    input("\n  按回车键返回菜单...")


def install_apk():
    print_header("安装到设备")
    print(f"  搜索已连接的设备...\n")
    subprocess.run(["adb", "devices"])
    print()
    apk = find_apk(APK_DEBUG_DIR)
    if apk:
        print(f"  安装 {os.path.basename(apk)} ...")
        result = subprocess.run(["adb", "install", "-r", apk])
        if result.returncode == 0:
            print(f"\n  [OK] 安装成功")
        else:
            print(f"\n  [!!] 安装失败，请检查设备连接")
    else:
        print(f"  [!!] 未找到 Debug APK，请先编译")
    input("\n  按回车键返回菜单...")


def clean_project():
    print_header("清理项目")
    if run_gradle("clean"):
        print(f"\n  [OK] 清理完成")
    else:
        print(f"\n  [!!] 清理失败")
    input("\n  按回车键返回菜单...")


def main():
    while True:
        os.system("cls" if os.name == "nt" else "clear")
        print()
        print("  ==========================================")
        print("     KIOSK 广告机 - 编译工具")
        print("  ==========================================")
        print()
        print("     1. 编译 Debug 版本")
        print("     2. 编译 Release 版本")
        print("     3. 清理并编译 Debug")
        print("     4. 清理并编译 Release")
        print("     5. 查看编译输出路径")
        print("     6. 安装到已连接的设备")
        print("     7. 清理项目")
        print("     0. 退出")
        print()
        choice = input("  请输入选项 [0-7]: ").strip()

        actions = {
            "1": build_debug,
            "2": build_release,
            "3": clean_build_debug,
            "4": clean_build_release,
            "5": show_output,
            "6": install_apk,
            "7": clean_project,
            "0": sys.exit,
        }

        action = actions.get(choice)
        if action:
            action()
        else:
            print("\n  无效选项，请重试")
            import time
            time.sleep(1.5)


if __name__ == "__main__":
    main()
