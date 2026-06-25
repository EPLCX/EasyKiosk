#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Kiosk 广告机 - 编译工具"""

import os
import sys
import subprocess

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
APK_DEBUG_DIR = os.path.join(PROJECT_DIR, "app", "build", "outputs", "apk", "debug")
APK_RELEASE_DIR = os.path.join(PROJECT_DIR, "app", "build", "outputs", "apk", "release")

GRADLE_CANDIDATES = [
    r"C:\Gradle\gradle-8.5\bin\gradle.bat",
    r"C:\Gradle\gradle-8.5\bin\gradle",
    "gradle",
]


def find_gradle():
    for path in GRADLE_CANDIDATES:
        if os.path.isfile(path):
            return path
    return "gradle"


def header(title):
    print(f"\n  {'=' * 46}")
    print(f"    {title}")
    print(f"  {'=' * 46}\n")


def run_gradle(*tasks):
    gradle = find_gradle()
    cmd = [gradle] + list(tasks) + ["--no-daemon"]
    print(f"  运行: {' '.join(cmd)}\n")
    return subprocess.run(cmd, cwd=PROJECT_DIR, shell=True).returncode == 0


def list_apks(directory):
    if not os.path.isdir(directory):
        return []
    return [
        os.path.join(directory, f)
        for f in sorted(os.listdir(directory))
        if f.endswith(".apk")
    ]


def print_apks(apks, label):
    if apks:
        print(f"  {label}:")
        for apk in apks:
            size = os.path.getsize(apk) // 1024
            print(f"    [OK] {os.path.basename(apk)} ({size} KB)")
    else:
        print(f"  [..] 未找到 {label}")


def wait():
    input("\n  按回车键返回菜单...")


def build_debug():
    header("编译 Debug 版本")
    if run_gradle("assembleDebug"):
        print_apks(list_apks(APK_DEBUG_DIR), "Debug APK")
    else:
        print("  [!!] 编译失败")
    wait()


def build_release():
    header("编译 Release 版本（已签名）")
    if run_gradle("assembleRelease"):
        print_apks(list_apks(APK_RELEASE_DIR), "Release APK")
    else:
        print("  [!!] 编译失败")
    wait()


def clean_build_debug():
    header("清理并编译 Debug 版本")
    if run_gradle("clean", "assembleDebug"):
        print_apks(list_apks(APK_DEBUG_DIR), "Debug APK")
    else:
        print("  [!!] 编译失败")
    wait()


def clean_build_release():
    header("清理并编译 Release 版本（已签名）")
    if run_gradle("clean", "assembleRelease"):
        print_apks(list_apks(APK_RELEASE_DIR), "Release APK")
    else:
        print("  [!!] 编译失败")
    wait()


def show_output():
    header("编译输出")
    print_apks(list_apks(APK_DEBUG_DIR), "Debug APK")
    print()
    print_apks(list_apks(APK_RELEASE_DIR), "Release APK")
    wait()


def install_apk():
    header("安装到设备")
    subprocess.run(["adb", "devices"])
    print()
    apks = list_apks(APK_DEBUG_DIR)
    arm64 = next((a for a in apks if "arm64-v8a" in a), apks[0] if apks else None)
    if arm64:
        print(f"  安装 {os.path.basename(arm64)} ...")
        result = subprocess.run(["adb", "install", "-r", arm64])
        print("  [OK] 安装成功" if result.returncode == 0 else "  [!!] 安装失败")
    else:
        print("  [!!] 未找到 Debug APK，请先编译")
    wait()


def clean_project():
    header("清理项目")
    print("  [OK] 清理完成" if run_gradle("clean") else "  [!!] 清理失败")
    wait()


MENU = [
    ("编译 Debug 版本",           build_debug),
    ("编译 Release 版本（已签名）", build_release),
    ("清理并编译 Debug",           clean_build_debug),
    ("清理并编译 Release（已签名）",clean_build_release),
    ("查看编译输出",               show_output),
    ("安装到已连接的设备",          install_apk),
    ("清理项目",                  clean_project),
]


def main():
    while True:
        os.system("cls" if os.name == "nt" else "clear")
        print()
        print("  ==========================================")
        print("     KIOSK 广告机 - 编译工具")
        print("  ==========================================")
        print()
        for i, (label, _) in enumerate(MENU, 1):
            print(f"     {i}. {label}")
        print("     0. 退出")
        print()
        choice = input("  请输入选项 [0-7]: ").strip()
        if choice == "0":
            sys.exit(0)
        try:
            idx = int(choice) - 1
            if 0 <= idx < len(MENU):
                MENU[idx][1]()
                continue
        except ValueError:
            pass
        print("\n  无效选项，请重试")
        __import__("time").sleep(1)


if __name__ == "__main__":
    main()
