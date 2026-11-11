# -*- coding: utf-8 -*-
"""
Onmyoji 模拟器自动脚本 - fast3.1 安全修复版
基于 fast3.0 改进：
1. 保留第一版的多线程GUI架构（主线程UI + 子线程业务）
2. 采用第二版的图像识别引擎（协战检测、超时保护、精确点击）
3. 统一日志系统，支持GUI窗口和死循环检测
4. 【fast2.1】连续六次拖拽时启动图像状态恢复：
   - 遍历图库中所有模板与当前桌面截图逐一比对
   - 确定当前游戏所处位置（BOSS/战斗/奖励/返回等）
   - 自动执行对应操作延续流程，而非直接退出
5. 【fast3.0新增】debug_screenshots 10次自动关机保护1
6. 【fast3.0新增】结界突破断线自动重连：
   - 每次查找前检测 disonline.png 断线提示
   - 自动点击 i_sure.png 确认重连
   - 断线后重新开始本轮结界突破，最多重试5次
   - 超过重试上限自动退出结界，恢复K28流程
7. 【fast3.1新增】PyAutoGUI fail-safe 安全修复：
   - 所有鼠标移动/点击操作前检查光标位置，防止触发 (0,0) 角落保护
   - moveRel/dragRel 操作后自动 clamp 至屏幕范围内
   - 新增 safe_click / safe_moveRel / safe_dragRel 封装函数
   - EmulatorWindow.click 内部加入 fail-safe 前检查
"""
import cv2
import numpy as np
import pyautogui
pyautogui.FAILSAFE = False  # 脚本自带角落救援/clamp逻辑，关闭fail-safe防止拖拽中碰鼠标导致崩溃
import pygetwindow as gw
import os
import random
import time
import sys
import datetime
import math
import threading
import queue
from collections import deque
from typing import Tuple, Optional, List
from pynput.keyboard import Controller

import tkinter as tk
from tkinter import simpledialog, messagebox
import tkinter.scrolledtext as st

# ========== 全局配置 ==========
CFG_MAX_REPEAT = 6  # 死循环检测次数
CFG_TIMEOUT = 90  # 操作超时时间(秒)
CFG_XSFY_COOLDOWN = 3  # 协战检测冷却时间(秒)
CFG_END_WAIT = 0.5  # 点击end前的强制等待时间(秒)
CFG_MAX_JJ_RETRY = 5  # 结界突破断线最大重试次数，超过则退出结界恢复K28
AUTO_INTERVAL = 1.0  # 连点器基准间隔(秒)
AUTO_FLUCT = 0.5  # 连点器间隔波动幅度(秒)
CFG_QW_TIMES = 8  # 结界突破第9关释放qw的轮数（k28+tp启动时询问）

# ---------- 全局变量 ----------
_log_queue = queue.Queue()
_stop_flag = threading.Event()
_recovery_flag = threading.Event()  # 拖拽循环恢复标志（fast2.1新增）
_debug_screenshot_count = 0  # debug_screenshots 计数器，达到10次则退出并关机
_log_window: Optional["LogWindow"] = None
kb = Controller()


# ========== fast3.1 新增：PyAutoGUI 安全封装 ==========
def _ensure_mouse_safe():
    """
    确保鼠标光标不在屏幕四角 (0,0), (W,0), (0,H), (W,H)，
    防止 PyAutoGUI fail-safe 触发。
    如果鼠标在角落，先移动到屏幕中央安全区域。
    """
    try:
        x, y = pyautogui.position()
        sw, sh = pyautogui.size()
        # 检测是否在四个角落（20px 容差）
        corner_ranges = [
            (0, 20, 0, 20),                    # 左上角
            (sw - 20, sw, 0, 20),              # 右上角
            (0, 20, sh - 20, sh),              # 左下角
            (sw - 20, sw, sh - 20, sh),        # 右下角
        ]
        for x1, x2, y1, y2 in corner_ranges:
            if x1 <= x <= x2 and y1 <= y <= y2:
                # 鼠标在角落，移动到屏幕中央。
                # 注意：救援moveTo自身也会过fail-safe检查（此时鼠标仍在角落必炸），
                # 必须临时关闭FAILSAFE再移动
                old = pyautogui.FAILSAFE
                pyautogui.FAILSAFE = False
                try:
                    pyautogui.moveTo(sw // 2, sh // 2)
                finally:
                    pyautogui.FAILSAFE = old
                return
    except Exception:
        pass


def safe_click(x: Optional[int] = None, y: Optional[int] = None):
    """
    安全的 pyautogui.click 封装。
    点击前检查鼠标位置，避免 fail-safe 触发。
    """
    _ensure_mouse_safe()
    if x is not None and y is not None:
        pyautogui.click(x, y)
    else:
        pyautogui.click()


def safe_move_rel(dx: int, dy: int, duration: float = 0.25):
    """
    安全的 pyautogui.moveRel 封装。
    移动后确保光标在屏幕范围内，不会卡在角落。
    """
    _ensure_mouse_safe()
    pyautogui.moveRel(dx, dy, duration=duration)
    # 移动后 clamp 到屏幕内，防止下次触发 fail-safe
    x, y = pyautogui.position()
    sw, sh = pyautogui.size()
    clamped_x = max(1, min(x, sw - 2))
    clamped_y = max(1, min(y, sh - 2))
    if clamped_x != x or clamped_y != y:
        pyautogui.moveTo(clamped_x, clamped_y)


def safe_move_to(x: int, y: int, duration: float = 0.25):
    """
    安全的 pyautogui.moveTo 封装。
    目标坐标 clamp 到屏幕范围内。
    """
    _ensure_mouse_safe()
    sw, sh = pyautogui.size()
    x = max(1, min(x, sw - 2))
    y = max(1, min(y, sh - 2))
    pyautogui.moveTo(x, y, duration=duration)


def safe_drag_rel(dx: int, dy: int, duration: float = 0.5):
    """
    安全的 pyautogui.dragRel 封装。
    拖拽前检查起始位置，拖拽后确保光标在屏幕内。
    """
    _ensure_mouse_safe()
    pyautogui.dragRel(dx, dy, duration=duration)
    # 拖拽后 clamp 到屏幕内
    x, y = pyautogui.position()
    sw, sh = pyautogui.size()
    clamped_x = max(1, min(x, sw - 2))
    clamped_y = max(1, min(y, sh - 2))
    if clamped_x != x or clamped_y != y:
        pyautogui.moveTo(clamped_x, clamped_y)


# ========== 日志窗口（主线程） ==========
class LogWindow:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("日志报告")
        self.root.configure(bg='white')
        self.root.attributes('-transparentcolor', 'white')
        self.root.attributes('-topmost', True)
        scr_w = self.root.winfo_screenwidth()
        self.root.geometry(f'420x400+{scr_w - 450}+40')
        self.root.resizable(True, True)

        self.text = st.ScrolledText(self.root, bg='white', fg='black',
                                    insertbackground='black', wrap=tk.WORD,
                                    font=('Consolas', 9))
        self.text.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)

        btn_frm = tk.Frame(self.root, bg='white')
        btn_frm.pack(side=tk.RIGHT, padx=5, pady=5)
        tk.Button(btn_frm, text='Exit', width=6, command=self._exit).pack(pady=2)
        tk.Button(btn_frm, text='Stop', width=6, command=self._stop_only).pack(pady=2)

        self.root.protocol('WM_DELETE_WINDOW', self._exit)
        self._poll()

    def _poll(self):
        """主线程轮询日志队列"""
        try:
            while True:
                line = _log_queue.get_nowait()
                self.text.insert(tk.END, line + '\n')
                self.text.see(tk.END)
        except queue.Empty:
            pass
        self.root.after(100, self._poll)

    def write(self, line: str):
        _log_queue.put(line)

    def _exit(self):
        os._exit(0)

    def _stop_only(self):
        _stop_flag.set()
        self.write('[日志窗口] 用户点击 Stop，脚本即将停止（窗口保留）')

    def run(self):
        self.root.mainloop()


# ========== 工具函数 ==========
def resource_path(relative_path: str) -> str:
    """获取打包后资源绝对路径"""
    if hasattr(sys, '_MEIPASS'):
        return os.path.join(sys._MEIPASS, relative_path)
    return os.path.join(os.path.abspath("."), relative_path)


# ========== 窗口封装（优化版） ==========
class EmulatorWindow:
    def __init__(self, title_keywords: List[str] = ("BlueStacks", "Nox", "LDPlayer", "MuMu", "雷电", "夜神", " mumu")):
        self.win = None
        for kw in title_keywords:
            try:
                windows = gw.getWindowsWithTitle(kw)
                if windows:
                    self.win = windows[0]
                    print(f"[+] 找到模拟器窗口：{self.win.title}")
                    break
            except Exception:
                continue

        if self.win is None:
            raise RuntimeError("未找到可用的安卓模拟器窗口，请确认已启动。")

        # 激活窗口
        try:
            if not self.win.isActive:
                self.win.activate()
                time.sleep(0.5)
        except Exception as e:
            print(f"[!] 窗口激活失败: {e}")

        time.sleep(0.5)

    def screenshot(self) -> np.ndarray:
        """带异常处理的截图"""
        try:
            img = pyautogui.screenshot(region=(self.win.left, self.win.top, self.win.width, self.win.height))
            return cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)
        except Exception as e:
            print(f"[!] 截图失败: {e}")
            time.sleep(1)
            # 重试一次
            img = pyautogui.screenshot(region=(self.win.left, self.win.top, self.win.width, self.win.height))
            return cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)

    def click(self, x: int, y: int):
        """带边界检查的点击 - fast3.1 加入 fail-safe 前检查"""
        abs_x = self.win.left + x
        abs_y = self.win.top + y
        # 确保点击在屏幕范围内
        screen_w, screen_h = pyautogui.size()
        abs_x = max(1, min(abs_x, screen_w - 2))
        abs_y = max(1, min(abs_y, screen_h - 2))
        # fast3.1: 点击前确保鼠标不在角落
        _ensure_mouse_safe()
        pyautogui.click(abs_x, abs_y)

    def swipe_rel(self, dx: int, dy: int, duration: float = 0.5):
        """相对滑动 - fast3.1 使用安全封装"""
        cx = self.win.left + self.win.width // 2
        cy = self.win.top + self.win.height // 2
        safe_move_to(cx, cy)
        safe_drag_rel(dx, dy, duration=duration)


# ========== 超时保护装饰器 ==========
def timeout_retry(timeout_sec: int = CFG_TIMEOUT):
    """装饰器：操作超时自动重试"""

    def decorator(func):
        def wrapper(*args, **kwargs):
            last_exception = None
            attempt = 0

            while True:
                attempt += 1
                start_time = time.time()
                result = None
                completed = False

                def target():
                    nonlocal result, completed
                    try:
                        result = func(*args, **kwargs)
                        completed = True
                    except Exception as e:
                        nonlocal last_exception
                        last_exception = e
                        completed = True

                thread = threading.Thread(target=target)
                thread.daemon = True
                thread.start()
                thread.join(timeout=timeout_sec)

                if completed:
                    if last_exception:
                        raise last_exception
                    return result
                else:
                    print(f"[!] 操作 {func.__name__} 超时({timeout_sec}s)，第{attempt}次重试...")
                    # 强制中断可能的阻塞操作
                    pyautogui.keyDown('esc')
                    pyautogui.keyUp('esc')
                    time.sleep(0.5)
                    continue

        return wrapper

    return decorator


# ========== 图像 Bot（整合优化版） ==========
class ImageBot:
    def __init__(self, emu: EmulatorWindow, tpl_dir: str, confidence: float = 0.8):
        self.emu = emu
        self.tpl_dir = tpl_dir
        self.confidence = confidence
        self._last_xsfy_check = 0  # 上次协战检测时间
        self._operation_count = 0  # 操作计数

        # 确保模板目录存在
        if not os.path.exists(tpl_dir):
            os.makedirs(tpl_dir)
            print(f"[!] 创建模板目录: {tpl_dir}")

    def _path(self, name: str) -> str:
        return os.path.join(self.tpl_dir, f"{name}.png")

    def _refuse_xsfy(self):
        """
        拒绝协战邀请 - 核心优化
        每次操作前调用，检测并拒绝协战
        """
        now = time.time()
        # 冷却时间内跳过，避免频繁检测
        if now - self._last_xsfy_check < CFG_XSFY_COOLDOWN:
            return

        self._last_xsfy_check = now

        # 先检测协战图标
        xsfy_pos = self._find_original("xsfy", check_xsfy=False)  # 避免递归
        if xsfy_pos:
            print(f"[协战] 检测到协战邀请 @ {xsfy_pos}")
            # 查找拒绝按钮（支持 yijujue 和 decline 两种命名）
            yjj_pos = self._find_original("yijujue", check_xsfy=False)
            if not yjj_pos:
                yjj_pos = self._find_original("decline", check_xsfy=False)

            if yjj_pos:
                self._click_direct(yjj_pos[0], yjj_pos[1])
                log("拒绝协战邀请", True)
                time.sleep(1)  # 等待关闭动画
            else:
                # 备用：直接点协战窗口外的区域关闭
                print("[协战] 未找到拒绝按钮，尝试点击空白处关闭")
                self.emu.click(50, 50)  # 左上角空白
                time.sleep(0.5)

    def _find_original(self, name: str, check_xsfy: bool = True) -> Optional[Tuple[int, int]]:
        """
        原始查找逻辑
        check_xsfy=False 用于协战检测本身，避免递归
        """
        # 协战检测（除非自身就是检测协战）
        if check_xsfy:
            self._refuse_xsfy()

        tpl_path = self._path(name)

        # 尝试常见变体
        if not os.path.exists(tpl_path):
            variants = [f"{name}.png", f"{name}.jpg", f"{name}_1.png", f"{name}.jpeg"]
            found = False
            for v in variants:
                alt_path = os.path.join(self.tpl_dir, v)
                if os.path.exists(alt_path):
                    tpl_path = alt_path
                    found = True
                    break
            if not found:
                return None

        try:
            screen = self.emu.screenshot()
            tpl = cv2.imread(tpl_path)
            if tpl is None:
                print(f"[!] 无法读取模板: {tpl_path}")
                return None

            res = cv2.matchTemplate(screen, tpl, cv2.TM_CCOEFF_NORMED)
            _, max_val, _, max_loc = cv2.minMaxLoc(res)

            if max_val < self.confidence:
                return None

            # 1/3边距内随机点击，模拟真人
            th, tw = tpl.shape[:2]
            x1, y1 = max_loc

            pad_w, pad_h = tw // 3, th // 3
            # 确保随机范围有效
            if tw - 2 * pad_w > 0 and th - 2 * pad_h > 0:
                x = random.randint(x1 + pad_w, x1 + tw - pad_w)
                y = random.randint(y1 + pad_h, y1 + th - pad_h)
            else:
                x, y = x1 + tw // 2, y1 + th // 2

            return (x, y)

        except Exception as e:
            print(f"[!] 图像匹配失败 [{name}]: {e}")
            return None

    def _click_direct(self, x: int, y: int, wait: float = 0.5):
        """直接点击坐标"""
        self.emu.click(x, y)
        if wait > 0:
            time.sleep(wait)

    def _click_original(self, name: str, wait: float = 0.5) -> bool:
        """原始点击逻辑"""
        pos = self._find_original(name, check_xsfy=True)  # 会触发协战检测
        if pos:
            self._click_direct(pos[0], pos[1], wait)
            return True
        return False

    # ===== 对外接口：带超时保护 =====
    @timeout_retry(CFG_TIMEOUT)
    def find(self, name: str) -> Optional[Tuple[int, int]]:
        """查找图片 - 带协战检测和超时保护"""
        self._operation_count += 1
        return self._find_original(name)

    @timeout_retry(CFG_TIMEOUT)
    def click(self, name: str, wait: float = 0.5) -> bool:
        """点击图片 - 带协战检测和超时保护"""
        self._operation_count += 1
        return self._click_original(name, wait)

    def find_center(self, name: str, check_xsfy: bool = True) -> Optional[Tuple[int, int]]:
        """
        查找模板并返回正中央坐标 - 用于需要精确点击的场景（如宝藏）
        """
        if check_xsfy:
            self._refuse_xsfy()

        tpl_path = self._path(name)
        if not os.path.exists(tpl_path):
            # 尝试变体
            variants = [f"{name}.png", f"{name}.jpg", f"{name}_1.png"]
            for v in variants:
                alt_path = os.path.join(self.tpl_dir, v)
                if os.path.exists(alt_path):
                    tpl_path = alt_path
                    break
            else:
                return None

        try:
            screen = self.emu.screenshot()
            tpl = cv2.imread(tpl_path)
            if tpl is None:
                return None

            h, w = tpl.shape[:2]
            res = cv2.matchTemplate(screen, tpl, cv2.TM_CCOEFF_NORMED)
            _, max_val, _, max_loc = cv2.minMaxLoc(res)

            if max_val < self.confidence:
                return None

            # 返回正中央坐标
            center_x = max_loc[0] + w // 2
            center_y = max_loc[1] + h // 2
            return (center_x, center_y)

        except Exception as e:
            print(f"[!] 查找中心失败 [{name}]: {e}")
            return None

    def click_center(self, name: str, wait: float = 0.5, context: str = "") -> bool:
        """
        点击模板正中央 - 用于宝藏等需要精确点击的按钮
        """
        action_name = context if context else f"点击 {name}(中央)"
        pos = self.find_center(name)
        if pos:
            self._click_direct(pos[0], pos[1], wait)
            print(f"[点击中央] {name} @ ({pos[0]}, {pos[1]})")
            return True
        return False

    # ===== fast2.1 新增：状态检测与恢复 =====
    # 状态检测专用置信度（比操作阈值更宽松，因为恢复时需要尽可能识别画面）
    DETECT_CONFIDENCE = 0.7

    # 多尺度匹配的缩放范围（适配不同模拟器窗口大小）
    DETECT_SCALES = [0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1.0]

    # 排除列表：这些模板是子组件/数字/动画帧，不应作为状态判断依据
    STATE_DETECT_EXCLUDE = {
        'crash', '1010', 'xsfy', 'yijujue', 'decline',
        'i_again',  # again按钮只在特定战斗结束场景出现，作为辅助判断
    }

    def detect_current_state(self, debug: bool = True) -> List[Tuple[str, float, Tuple[int, int]]]:
        """
        遍历图库中所有模板图像，与当前桌面截图逐一比对（支持多尺度匹配）。
        返回所有匹配到的 (模板名, 置信度, 坐标) 列表，按置信度降序排列。
        用于拖拽循环后确定当前游戏所处位置。

        debug=True 时会同时输出未达阈值的接近匹配，方便排查。
        """
        screen = self.emu.screenshot()
        screen_h, screen_w = screen.shape[:2]
        results: List[Tuple[str, float, Tuple[int, int]]] = []
        near_misses: List[Tuple[str, float]] = []

        if not os.path.exists(self.tpl_dir):
            print(f"[!] 图库目录不存在: {self.tpl_dir}")
            return results

        for filename in os.listdir(self.tpl_dir):
            if not filename.lower().endswith(('.png', '.jpg', '.jpeg')):
                continue

            tpl_path = os.path.join(self.tpl_dir, filename)
            tpl_original = cv2.imread(tpl_path)
            if tpl_original is None:
                continue

            name = os.path.splitext(filename)[0]

            # 跳过排除列表中的模板
            if name in self.STATE_DETECT_EXCLUDE:
                continue

            try:
                best_val = 0.0
                best_loc = (0, 0)

                # ===== 多尺度匹配 =====
                for scale in self.DETECT_SCALES:
                    # 缩放模板
                    new_w = int(tpl_original.shape[1] * scale)
                    new_h = int(tpl_original.shape[0] * scale)

                    # 缩放后太小或比屏幕大则跳过
                    if new_w < 10 or new_h < 10:
                        continue
                    if new_w > screen_w or new_h > screen_h:
                        continue

                    tpl_scaled = cv2.resize(tpl_original, (new_w, new_h),
                                            interpolation=cv2.INTER_AREA if scale < 1.0 else cv2.INTER_LINEAR)

                    res = cv2.matchTemplate(screen, tpl_scaled, cv2.TM_CCOEFF_NORMED)
                    _, max_val, _, max_loc = cv2.minMaxLoc(res)

                    if max_val > best_val:
                        best_val = max_val
                        best_loc = max_loc

                # 保存最佳匹配结果
                if best_val >= self.DETECT_CONFIDENCE:
                    results.append((name, best_val, best_loc))
                elif debug and best_val >= self.DETECT_CONFIDENCE - 0.15:
                    near_misses.append((name, best_val))

            except Exception as e:
                print(f"[!] 状态检测-匹配失败 [{filename}]: {e}")
                continue

        # 按置信度降序排列
        results.sort(key=lambda x: x[1], reverse=True)
        near_misses.sort(key=lambda x: x[1], reverse=True)

        # 输出调试信息
        if debug and near_misses:
            print(f"[DEBUG] 接近匹配（未达阈值 {self.DETECT_CONFIDENCE}）:")
            for n, conf in near_misses[:8]:
                print(f"  → {n} (置信度: {conf:.2%})")

        return results


# ========== 日志系统（整合版） ==========
_last_keys: deque[tuple[str, bool]] = deque(maxlen=CFG_MAX_REPEAT)


def log(action: str, ok: bool):
    """带死循环检测的日志 - 同时输出到控制台和GUI"""
    now = datetime.datetime.now().strftime("%H:%M:%S")
    status = "成功" if ok else "失败"
    line = f"{now} [操作] {action} {status}"

    print(line)

    # 写入GUI日志窗口
    if _log_window:
        _log_window.write(line)

    # 死循环检测
    key = (action, ok)
    _last_keys.append(key)

    if len(_last_keys) == CFG_MAX_REPEAT and len(set(_last_keys)) == 1:
        # fast2.1: 连续六次拖拽 → 启动状态恢复，而非直接退出
        if action == "拖拽找怪":
            error_msg = f'[RECOVERY] 连续{CFG_MAX_REPEAT}次拖拽找怪，启动图像状态恢复...'
            print(error_msg)
            if _log_window:
                _log_window.write(error_msg)
            _recovery_flag.set()
            _last_keys.clear()  # 清空记录，让恢复后的操作重新计数
        else:
            error_msg = f'[ANTI-LOOP] 连续{CFG_MAX_REPEAT}次相同操作，判定死循环，程序终止！'
            print(error_msg)
            if _log_window:
                _log_window.write(error_msg)
            os._exit(1)


# ========== fast2.1 新增：状态恢复逻辑 ==========
def _save_debug_screenshot(emu: EmulatorWindow, tag: str = "unknown"):
    """保存调试截图，用于排查状态恢复失败原因。
    同时累计调用次数，达到 10 次则退出脚本并关机。"""
    global _debug_screenshot_count
    _debug_screenshot_count += 1
    print(f"[DEBUG] 调试截图计数: {_debug_screenshot_count}/10")

    # 达到 10 次 debug_screenshots → 退出脚本并关机
    if _debug_screenshot_count >= 10:
        error_msg = (
            f"[SHUTDOWN] debug_screenshots 已达 {_debug_screenshot_count} 次，"
            f"脚本即将退出并关机..."
        )
        print(error_msg)
        if _log_window:
            _log_window.write(error_msg)
        # 先保存最后一张截图用于排查
        try:
            debug_dir = "debug_screenshots"
            if not os.path.exists(debug_dir):
                os.makedirs(debug_dir)
            ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
            final_path = os.path.join(debug_dir, f"recover_{tag}_FINAL_{ts}.png")
            screen = emu.screenshot()
            cv2.imwrite(final_path, screen)
            print(f"[DEBUG] 最终调试截图已保存: {final_path}")
        except Exception:
            pass
        os.system("shutdown /s /t 10")
        os._exit(1)

    try:
        debug_dir = "debug_screenshots"
        if not os.path.exists(debug_dir):
            os.makedirs(debug_dir)
        ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        path = os.path.join(debug_dir, f"recover_{tag}_{ts}.png")
        screen = emu.screenshot()
        cv2.imwrite(path, screen)
        print(f"[DEBUG] 调试截图已保存: {path}")
        return path
    except Exception as e:
        print(f"[!] 保存调试截图失败: {e}")
        return None


def recover_state(bot: ImageBot, emu: EmulatorWindow) -> bool:
    """
    拖拽循环后的状态恢复机制。
    通过对比桌面截图与图库中所有图像，确定当前游戏所处位置，
    并按优先级使用已检测到的坐标直接点击（避免二次搜索失败），
    使流程得以延续。
    返回 True 表示恢复成功，False 表示失败。
    """
    log("===== 状态恢复开始 =====", True)
    log("正在扫描当前画面并与图库比对...", True)

    # 保存恢复前截图用于调试
    _save_debug_screenshot(emu, "before_recover")

    matches = bot.detect_current_state(debug=True)

    if not matches:
        log("状态恢复失败 - 未匹配到任何已知图像", False)
        _save_debug_screenshot(emu, "no_match")
        return False

    # 构建 模板名 → (置信度, 坐标) 的映射
    match_map: dict[str, Tuple[float, Tuple[int, int]]] = {}
    for name, conf, pos in matches:
        if name not in match_map or conf > match_map[name][0]:
            match_map[name] = (conf, pos)

    # 输出匹配结果
    log(f"共匹配到 {len(match_map)} 种图像（总计{len(matches)}个匹配）:", True)
    for name, (conf, pos) in list(match_map.items())[:10]:
        log(f"  → {name} @ ({pos[0]}, {pos[1]}) 置信度: {conf:.2%}", True)

    # ========== 状态→操作映射 ==========
    # 优先级：弹窗/对话框 → 结束/奖励界面 → 战斗/BOSS → 返回导航 → 入口
    # 每个条目: (模板名, 操作描述, 点击坐标或动作, 等待时间)
    # 注意：恢复时直接使用检测到的坐标点击，而非重新搜索，避免二次匹配失败

    def _click_match(name: str, wait: float = 0.5):
        """使用检测阶段缓存的坐标直接点击，不做二次搜索"""
        if name in match_map:
            _, pos = match_map[name]
            bot._click_direct(pos[0], pos[1], wait)
            return True
        return False

    # 按优先级排列的状态处理表
    state_actions: List[Tuple[str, str, callable, float]] = [
        # === 弹窗/对话框类（最高优先级，需先处理才能继续） ===
        ('disonline_sure', '断线重连确认',  lambda: _click_match('disonline_sure', 1),      1.5),
        ('disonline',      '断线提示',      lambda: _click_match('disonline', 1),            1.0),
        ('i_sure',         '确认按钮',      lambda: _click_match('i_sure', 0.5),             1.0),
        ('i_off',          '关闭按钮',      lambda: _click_match('i_off', 0.5),             0.5),
        ('offtp',          '关闭组队',      lambda: _click_match('offtp', 0.5),             0.5),
        ('gao',            '通知/公告',     lambda: emu.click(emu.win.width // 2, emu.win.height // 2), 1.0),
        ('zudui',          '组队邀请',      lambda: _click_match('i_off', 0.5) or _click_match('offtp', 0.5) or emu.click(emu.win.width // 2, emu.win.height - 100), 1.0),

        # === 协战邀请 ===
        ('xsfy',           '协战邀请',      lambda: _click_match('yijujue', 0.5) or _click_match('decline', 0.5) or emu.click(50, 50), 1.0),

        # === 30人活动 ===
        ('30man1',         '30人活动页1',   lambda: _click_match('i_off', 0.5) or emu.click(emu.win.width - 50, 50), 1.0),
        ('30man2',         '30人活动页2',   lambda: _click_match('i_off', 0.5) or emu.click(emu.win.width - 50, 50), 1.0),

        # === 结束/奖励界面 ===
        ('i_end',          '战斗结束界面',  lambda: _click_match('i_end', 1),                1.5),
        ('i_paper',        '纸人奖励',      lambda: _click_match('i_paper', 0.5),            0.5),
        ('i_treasure',     '宝藏',          lambda: _click_match('i_treasure', 0.5),         0.5),

        # === 再次挑战 ===
        ('i_again',        '再次挑战',      lambda: _click_match('i_again', 1),              1.0),

        # === 战斗 ===
        ('i_boss',         'BOSS战斗',      lambda: _click_match('i_boss', 1),               1.0),
        ('i_zhandou',      '普通战斗',      lambda: _click_match('i_zhandou', 1),            1.0),

        # === 返回导航（点击返回回到上一级） ===
        ('fanhui',         '返回按钮',      lambda: _click_match('fanhui', 1),               1.0),
        ('fanhui1',        '返回按钮1',     lambda: _click_match('fanhui1', 1),              1.0),
        ('fanhui2',        '返回按钮2',     lambda: _click_match('fanhui2', 1),              1.0),
        ('i_back',         '后退按钮',      lambda: _click_match('i_back', 1),              1.0),

        # === 入口 ===
        ('i_tansuo',       '探索入口',      lambda: _click_match('i_tansuo', 1),             1.0),
        ('i_tou',          '战斗头像',      lambda: _click_match('i_tou', 1),                1.0),
        ('i_28',           'K28主页',       lambda: _click_match('i_28', 1),                 1.0),
        ('i_jj',           '结界入口',      lambda: _click_match('i_jj', 1),                 1.0),
        ('yuhun',          '御魂界面',      lambda: _click_match('yuhun', 1),                1.0),
        ('easy-znq',       '爬塔界面',      lambda: _click_match('easy-znq', 1),             1.0),
    ]

    # 按优先级尝试操作
    for state_name, desc, action, wait_time in state_actions:
        if state_name in match_map:
            log(f"检测到当前状态: [{desc}]，执行对应操作...", True)
            try:
                result = action()
                time.sleep(wait_time)
                _recovery_flag.clear()
                _last_keys.clear()
                if result or result is None:
                    log(f"===== 状态恢复完成 - 已从 [{desc}] 继续流程 =====", True)
                    return True
                else:
                    log(f"恢复操作 [{desc}] 返回失败，尝试下一个匹配...", False)
                    continue
            except Exception as e:
                log(f"恢复操作 [{desc}] 执行异常: {e}", False)
                continue

    # 兜底：有匹配但都不在已知处理列表中，尝试通用策略
    log("匹配到的图像无对应处理逻辑，尝试通用恢复...", False)

    # 策略1：逐个点击所有匹配到的可点击元素
    for name, (conf, pos) in match_map.items():
        try:
            log(f"通用恢复: 点击 {name} @ ({pos[0]}, {pos[1]})", True)
            bot._click_direct(pos[0], pos[1], 0.5)
            time.sleep(1)
        except Exception:
            continue

    _recovery_flag.clear()
    _last_keys.clear()
    log("通用恢复完成（已点击所有匹配元素）", True)
    _save_debug_screenshot(emu, "after_generic")
    return True


# ========== 业务逻辑 ==========
def do_jj_break(bot: ImageBot, emu: EmulatorWindow):
    """结界突破流程 - 使用优化后的图像识别（支持断线自动重连）"""

    def _check_disonline() -> bool:
        """检测断线提示 disonline 并点击 i_sure 确认。
        若图片存在则处理断线，返回 True 表示发生了断线重连。"""
        try:
            pos = bot.find("disonline")
            if pos:
                log("检测到断线提示 disonline", False)
                time.sleep(0.5)
                if bot.click("i_sure", wait=1):
                    log("点击 i_sure 确认断线，准备重新开始结界突破", True)
                else:
                    log("未找到 i_sure，尝试点击断线提示区域", False)
                    bot._click_direct(pos[0], pos[1], 1)
                time.sleep(3)  # 等待重连/界面恢复
                return True
        except Exception as e:
            print(f"[!] 断线检测异常: {e}")
        return False

    # 外层循环 — 断线后重新开始，最多重试 CFG_MAX_JJ_RETRY 次
    jj_retry = 0
    while jj_retry < CFG_MAX_JJ_RETRY:
        if _stop_flag.is_set():
            log("用户停止", False)
            return

        jj_retry += 1
        log(f"结界突破 第{jj_retry}/{CFG_MAX_JJ_RETRY}次尝试", True)

        time.sleep(2)

        # 进入结界
        if not bot.find("i_jj"):
            # fast3.1: home 与 dl 往复寻找（先 home 后 dl），直到出现 i_28 为止，
            # 然后进行原本的流程（找 i_jj → 进入结界）
            log("未找到 i_jj，home/dl 往复寻找直到出现 i_28", False)
            jj_start = time.time()
            round_cnt = 0
            dl_pos = (1457, 461)  # fast3.1: dl 兜底位置（用户提供），搜不到时直接点这里；搜到后更新
            while not _stop_flag.is_set():
                round_cnt += 1
                if round_cnt > 10:
                    # fast5.1: 最多往复10轮，防止 i_28 永远匹配不到导致无限点击
                    log("home/dl 往复超过10轮仍未出现 i_28，放弃本次结界突破", False)
                    return
                # fast3.1: home 只搜图点击，不做任何默认/记忆位置点击
                if bot.click("home", wait=2):
                    log(f"点击 home 图标(第{round_cnt}轮)", True)
                # fast3.1: dl 优先搜图，搜不到且有记忆位置则点记忆位置
                dl_found = bot.find("dl")
                if dl_found:
                    dl_pos = dl_found
                    bot._click_direct(dl_found[0], dl_found[1], 2)
                    log(f"点击 dl(第{round_cnt}轮)", True)
                elif dl_pos:
                    bot._click_direct(dl_pos[0], dl_pos[1], 2)
                    log(f"未搜到 dl，点击记忆位置 (第{round_cnt}轮)", True)
                else:
                    log(f"搜索 dl 失败(第{round_cnt}轮)", False)
                if bot.find("i_28"):
                    log("已出现 i_28，回到原本流程", True)
                    break
                if bot.find("i_jj"):
                    log("已出现 i_jj，回到原本流程", True)
                    break
                # fast3.1: 保底机制——每4轮没等到 i_28 就做一次图像状态恢复
                if round_cnt % 4 == 0:
                    log("home/dl 往复4轮未出现 i_28，触发图像状态恢复...", False)
                    if not recover_state(bot, emu):
                        log("状态恢复失败，放弃本次结界突破", False)
                        return
                # 未出现则带轮数日志重来，避免死循环检测误判
                log(f"home/dl 往复一轮未出现 i_28（第{round_cnt}轮）", False)
                # fast3.1: 给页面加载留时间，拉长轮间隔
                time.sleep(5)

        if _check_disonline():
            continue

        bot.click("i_jj", wait=1)
        log("进入结界突破", True)
        time.sleep(2)

        # 1-9关循环
        restart = False
        for i in range(1, 10):
            if _stop_flag.is_set():
                log("用户停止", False)
                return

            if _check_disonline():
                restart = True
                break

            kb.tap(str(i))
            log(f"JJ 第{i}关", True)
            time.sleep(2)

            if _check_disonline():
                restart = True
                break

            # 查找并点击crash
            pos = bot.find("crash")
            if pos:
                bot.click("crash", wait=4)
                log("点击 crash", True)
                time.sleep(2)
            else:
                log("搜索 crash 失败", False)
                if _check_disonline():
                    restart = True
                    break
                continue

            # 第9关放技能（次数由启动时询问决定）
            if i == 9:
                for _ in range(CFG_QW_TIMES):
                    if _stop_flag.is_set():
                        break
                    time.sleep(1)
                    kb.tap("q")
                    log("JJ 点击 q", True)
                    time.sleep(1)
                    kb.tap("w")
                    log("JJ 点击 w", True)
                    time.sleep(3)

                    # 等待再次挑战按钮
                    start_wait = time.time()
                    while not bot.find("i_again"):
                        if time.time() - start_wait > 30 or _stop_flag.is_set():
                            break
                        if _check_disonline():
                            break
                        time.sleep(1)
                    if _stop_flag.is_set():
                        break
                    if _check_disonline():
                        break
                    bot.click("i_again", wait=1)
                    log("again", True)
                    time.sleep(2)

            if _check_disonline():
                restart = True
                break

            time.sleep(4)

            # 等待结束 - 点击i_end前强制等待
            start_wait = time.time()
            while True:
                if _stop_flag.is_set():
                    log("用户停止", False)
                    return
                if _check_disonline():
                    restart = True
                    break
                if time.time() - start_wait > 90:
                    log("等待 i_end 超时", False)
                    while True:
                        if _check_disonline():
                            restart = True
                            break
                        if bot.find("i_end"):
                            bot.click("i_end", wait=0.7)
                            log("点击end", True)
                        time.sleep(CFG_END_WAIT)
                        if bot.find("fanhui1"):
                            bot.click("fanhui1", wait=0.7)
                            log("点击fanhui1", True)
                        time.sleep(CFG_END_WAIT)
                        if bot.find("fanhui"):
                            bot.click("fanhui", wait=1.5)
                            log("点击fanhui", True)
                            return
                        if bot.find("i_28"):
                            return

                # 先查找，找到后等待再点击
                pos = bot.find("i_end")
                if pos:
                    time.sleep(CFG_END_WAIT)
                    bot.click("i_end", wait=3)
                    time.sleep(1)
                    log("JJ 点击 i_end", True)
                    break

            if restart:
                break

            # 阶段奖励（每3关）
            if i % 3 == 0:
                start_wait = time.time()
                while time.time() - start_wait < 10:
                    if _stop_flag.is_set():
                        break
                    if _check_disonline():
                        restart = True
                        break
                    pos = bot.find("i_end")
                    if pos:
                        time.sleep(CFG_END_WAIT)
                        bot.click("i_end", wait=1)
                        log("JJ 阶段奖励", True)
                        time.sleep(2)
                        break

        # 如果for循环因断线中断，重新开始
        if restart:
            continue

        # 返回
        time.sleep(2)
        start_wait = time.time()
        while time.time() - start_wait < 30:
            if _stop_flag.is_set():
                return
            if _check_disonline():
                restart = True
                break
            pos = bot.find("fanhui")
            if pos:
                bot.click("fanhui", wait=1)
                log("JJ 返回 K28", True)
                return
            time.sleep(1)

        if restart:
            continue

        log("JJ 返回失败", False)
        return

    # === 超过最大重试次数，强制退出结界并恢复K28 ===
    log(f"结界突破断线重试已达上限({CFG_MAX_JJ_RETRY}次)，强制退出结界...", False)
    for _ in range(3):
        if bot.click("fanhui1", wait=1) or bot.click("fanhui", wait=1):
            log("已通过 fanhui 退出结界，恢复K28流程", True)
            return
        time.sleep(1)
    log("无法退出结界，放弃本次结界突破", False)


def run_k28(bot: ImageBot, emu: EmulatorWindow, times: int, luns: int):
    """K28探索主循环 - 使用优化后的图像识别（fast3.1 安全修复版）"""
    cycle_cnt = 0

    for i in range(times):
        if _stop_flag.is_set():
            log("用户停止", False)
            return

        print(f"\n========== 第 {i + 1}/{times} 次循环 ==========")

        # 插入结界突破
        if luns > 0 and (i + 1) % luns == 0:
            cycle_cnt += 1
            print(f"--- 第{cycle_cnt}轮{luns}次已满，插入结界突破 ---")
            do_jj_break(bot, emu)
            if _stop_flag.is_set():
                return
            print("--- 结界突破完成，继续K28 ---")

        # 进入探索
        start_time = time.time()
        last_progress_time = time.time()

        while time.time() - start_time < CFG_TIMEOUT:
            if _stop_flag.is_set():
                log("用户停止", False)
                return

            pos = bot.find("i_tansuo")
            if pos:
                bot.click("i_tansuo", wait=1)
                log("点击 i_tansuo", True)
                # fast3.1: 校验是否真的进入章节（i_zhandou/i_boss 出现），
                # 页面加载失败时不 break，留在本循环重新点击
                enter_start = time.time()
                entered = False
                while time.time() - enter_start < 15 and not _stop_flag.is_set():
                    if bot.find("i_zhandou") or bot.find("i_boss"):
                        entered = True
                        break
                    time.sleep(1)
                if entered:
                    break
                log("点击探索后未进入章节，重新点击", False)
                continue

            if bot.click("i_28", wait=1):
                log("点击 i_28", True)
                last_progress_time = time.time()
                continue

            time.sleep(0.5)

        # 找BOSS或战斗
        boss_found = False
        search_start = time.time()

        while not boss_found and time.time() - search_start < CFG_TIMEOUT * 2:
            if _stop_flag.is_set():
                log("用户停止", False)
                return

            # fast2.1: 检测拖拽循环恢复标志
            if _recovery_flag.is_set():
                log("检测到拖拽循环，进入状态恢复...", False)
                if recover_state(bot, emu):
                    # 恢复成功，重置搜索时间，继续寻找BOSS/战斗
                    search_start = time.time()
                    continue
                else:
                    log("状态恢复失败，终止进程", False)
                    os._exit(1)

            if bot.click("i_boss", wait=0.5):
                boss_found = True
                log("点击 i_boss", True)
            elif bot.click("i_zhandou", wait=0.5):
                log("点击 i_zhandou", True)
                time.sleep(6.3)
                # fast3.1: 使用安全点击
                safe_click()
                time.sleep(0.3)
                safe_click()
                time.sleep(0.3)

                # 等待战斗结束
                fight_start = time.time()
                while time.time() - fight_start < CFG_TIMEOUT:
                    if _stop_flag.is_set():
                        break
                    pos = bot.find("i_end")
                    if pos:
                        time.sleep(CFG_END_WAIT)
                        bot.click("i_end", wait=1)
                        log("点击 i_end(战斗)", True)
                        break
                    time.sleep(2)
                    if bot.find("i_zhandou"):
                        bot.click("i_zhandou", wait=1)
                # fast3.1: 战斗打完重置找怪计时器，战斗时间不计入180s总超时
                search_start = time.time()
            else:
                # 滑动找怪 - fast3.1: 使用安全封装
                cx, cy = emu.win.width // 2, emu.win.height // 2
                safe_move_to(emu.win.left + cx, emu.win.top + cy)
                emu.swipe_rel(random.randint(-800, -500), 0, 0.5)
                log("拖拽找怪", True)
                time.sleep(0.5)

        # BOSS战
        if boss_found:
            time.sleep(6.5)
            # fast3.1: 使用安全点击
            safe_click()
            time.sleep(0.3)
            safe_click()
            time.sleep(0.3)
            boss_start = time.time()
            while time.time() - boss_start < CFG_TIMEOUT:
                if _stop_flag.is_set():
                    break
                pos = bot.find("i_end")
                if pos:
                    time.sleep(CFG_END_WAIT)
                    bot.click("i_end", wait=0.5)
                    log("点击 i_end(BOSS)", True)
                    break
                time.sleep(0.5)

        # 纸人奖励
        for k in range(1, 5):
            if _stop_flag.is_set():
                break
            paper_start = time.time()
            clicked = False
            while time.time() - paper_start < 3:
                if bot.click("i_paper", wait=0.3):
                    log(f"第{k}次纸人", True)
                    # fast3.1: 使用安全 moveRel，替代原有的 pyautogui.moveRel
                    safe_move_rel(0, 300)
                    safe_click()
                    time.sleep(0.5)
                    clicked = True
                    break
            if not clicked:
                log(f"第{k}次纸人未找到", False)
                if k >= 3:
                    break

        # 宝箱 - 使用精确中央点击1
        if bot.click_center("i_treasure", wait=0.5, context="点击宝藏中央"):
            time.sleep(2)
            treasure_start = time.time()
            while time.time() - treasure_start < CFG_TIMEOUT:
                if _stop_flag.is_set():
                    break
                if bot.find("i_end"):
                    time.sleep(CFG_END_WAIT)
                    bot.click("i_end")
                    log("点击 i_end(宝藏)", True)
                    break
                time.sleep(1)
        else:
            log("搜索 i_treasure", False)


def run_jj_tower(bot: ImageBot):
    """结界塔模式 - 使用优化后的图像识别"""
    time.sleep(5)
    for j in range(1, 4):
        for i in range(1, 10):
            if _stop_flag.is_set():
                log("用户停止", False)
                return

            kb.tap(str(i))
            log(f"JJ第{j}层第{i}关", True)
            time.sleep(1)

            # 查找并点击crash
            pos = bot.find("crash")
            if pos:
                bot.click("crash", wait=4)
                log("点击 crash", True)
                time.sleep(2)
            else:
                log("搜索 crash 失败", False)
                continue

            if i == 9:
                for _ in range(4):
                    if _stop_flag.is_set():
                        break
                    time.sleep(1)
                    kb.tap("q")
                    log("JJ点击q", True)
                    time.sleep(1)
                    kb.tap("w")
                    log("JJ点击w", True)
                    time.sleep(3)

                    start_wait = time.time()
                    while not bot.find("i_again") and time.time() - start_wait < 20:
                        if _stop_flag.is_set():
                            break
                        time.sleep(1)
                    if _stop_flag.is_set():
                        break
                    bot.click("i_again", wait=1)
                    log("again", True)
                    time.sleep(3)

            time.sleep(4)

            # 点击i_end前强制等待
            start_wait = time.time()
            while time.time() - start_wait < 60:
                if _stop_flag.is_set():
                    break
                pos = bot.find("i_end")
                if pos:
                    time.sleep(CFG_END_WAIT)
                    bot.click("i_end", wait=3)
                    log("JJ点击i_end", True)
                    break

            # 阶段奖励
            if i % 3 == 0:
                start_wait = time.time()
                while time.time() - start_wait < 10:
                    if _stop_flag.is_set():
                        break
                    pos = bot.find("i_end")
                    if pos:
                        time.sleep(CFG_END_WAIT)
                        bot.click("i_end", wait=1)
                        log("JJ阶段奖励", True)
                        time.sleep(2)
                        break


def run_pata(bot: ImageBot, ticket_num: int, waste_time: int):
    """爬塔模式 - 使用优化后的图像识别（fast3.1 安全修复版）"""
    for i in range(ticket_num):
        if _stop_flag.is_set():
            log("用户停止", False)
            return

        if bot.find("easy-znq"):
            bot.click("easy-znq", wait=random.uniform(2, 4))
            # fast3.1: 使用安全 moveRel，替代原有的 pyautogui.moveRel
            safe_move_rel(random.randint(-50, 50), random.randint(-300, -200))
            log("点击 easy-znq", True)
            time.sleep(random.uniform(waste_time, waste_time + 2))
            safe_move_rel(random.randint(-50, 30), random.randint(-300, -100))
            # fast3.1: 使用安全点击
            safe_click()
            log("完成 easy-znq", True)
            time.sleep(random.uniform(1, 3))
        else:
            time.sleep(2)
            log("搜索 easy-znq", False)
            # fast3.1: 使用安全点击
            safe_click()


def run_tiaozhan(bot: ImageBot, ticket_num: int, waste_time: int):
    """挑战模式 - 逻辑与周年庆（easy-znq）一致，使用 tiaozhan 图片"""
    for i in range(ticket_num):
        if _stop_flag.is_set():
            log("用户停止", False)
            return

        if bot.find("tiaozhan"):
            time.sleep(1)  # 搜到后等1秒再点
            bot.click("tiaozhan", wait=random.uniform(2, 4))
            log("点击 tiaozhan", True)
            # 先等输入的刷取时间，再向上拖动，之后点击
            time.sleep(random.uniform(waste_time, waste_time + 2))
            safe_move_rel(random.randint(-20, 20), random.randint(-100, -60))
            safe_click()
            # 搜索并点击 i_end
            end_start = time.time()
            while time.time() - end_start < CFG_TIMEOUT:
                if _stop_flag.is_set():
                    break
                if bot.find("i_end"):
                    time.sleep(1)  # 搜到后等1秒再点
                    bot.click("i_end", wait=1)
                    log("点击 i_end(挑战)", True)
                    break
                time.sleep(1)
            log("完成 tiaozhan", True)
            time.sleep(random.uniform(1, 3))
        else:
            time.sleep(2)
            log("搜索 tiaozhan", False)
            safe_click()


def run_yuhun(bot: ImageBot, yuhun_time: int):
    """御魂模式 - 使用优化后的图像识别（fast3.1 安全修复版）"""
    time.sleep(5)

    for i in range(yuhun_time):
        if _stop_flag.is_set():
            log("用户停止", False)
            return

        time.sleep(2)
        if bot.find("yuhun"):
            bot.click("yuhun", wait=1)
            time.sleep(10)
            # fast3.1: 使用安全 moveRel，替代原有的 pyautogui.moveRel
            safe_move_rel(random.randint(-400, -300), 0)
            time.sleep(10)
            # fast3.1: 使用安全点击
            safe_click()
            time.sleep(2.4)
            safe_click()
            time.sleep(2.4)
            safe_click()


# ========== fast3.1 新增：连点器模式 ==========
def run_autoclicker():
    """
    连点器：
    1. 用户移动鼠标到目标位置，点击左键确定中心点
    2. 输入点击间隔和上下波动区间
    3. 点击间隔按正弦规律在 [间隔-波动, 间隔+波动] 之间上下波动
    4. 点击日志窗口的 Stop 或 Exit 停止
    """
    from pynput import mouse

    log("请在3秒内将鼠标移动到目标位置并【左键单击】确定中心点...", True)
    time.sleep(3)

    click_event = threading.Event()
    click_pos: List[int] = [0, 0]

    def on_click(x, y, button, pressed):
        if pressed and button == mouse.Button.left:
            click_pos[0], click_pos[1] = x, y
            click_event.set()
            return False  # 停止监听

    listener = mouse.Listener(on_click=on_click)
    listener.start()
    log("等待鼠标左键点击...", True)
    if not click_event.wait(timeout=30):
        listener.stop()
        log("30秒内未检测到鼠标点击，连点器退出", False)
        return
    log(f"中心点已确定: ({click_pos[0]}, {click_pos[1]})", True)

    # 间隔和波动区间（主线程弹窗已在 main 中收集）
    log(f"开始连点：间隔约 {AUTO_INTERVAL}s ± {AUTO_FLUCT}s 正弦波动，点 Stop 停止", True)

    phase = 0.0
    cycle = max(AUTO_FLUCT * 20, 20)  # 波动周期(秒)：波动越大周期越长，最小20秒
    while not _stop_flag.is_set():
        # 正弦波动：间隔在 [interval-fluct, interval+fluct] 之间规律变化
        cur_interval = AUTO_INTERVAL + AUTO_FLUCT * math.sin(2 * math.pi * phase / cycle)
        phase = (phase + cur_interval) % cycle

        pyautogui.click(click_pos[0], click_pos[1])
        time.sleep(max(0.05, cur_interval))

    log("连点器已停止", True)


# ========== 弹窗（主线程） ==========
def ask_choice() -> str:
    root = tk.Tk()
    root.withdraw()
    choice = simpledialog.askstring("选择副本", "请输入数字：\n1. k28+tp\n2. tp*3\n3. 爬塔\n4. 御魂\n5. 连点器\n6. 挑战")
    root.quit()
    root.destroy()
    return choice


def ask_int(prompt: str, default: int = 0) -> int:
    root = tk.Tk()
    root.withdraw()
    while True:
        try:
            value = simpledialog.askstring("输入", prompt)
            root.quit()
            root.destroy()
            return int(value or str(default))
        except ValueError:
            messagebox.showerror("输入错误", "请输入一个有效的整数")


def ask_float(prompt: str, default: float = 0.0) -> float:
    """弹窗输入浮点数"""
    root = tk.Tk()
    root.withdraw()
    while True:
        try:
            value = simpledialog.askstring("输入", prompt)
            root.quit()
            root.destroy()
            return float(value or str(default))
        except ValueError:
            messagebox.showerror("输入错误", "请输入一个有效的数字")


# ========== 主入口 ==========
def main():
    global _log_window

    # 1. 主线程创建日志窗口
    _log_window = LogWindow()
    _log_window.write("=" * 50)
    _log_window.write("Onmyoji 自动化脚本 - fast3.1 安全修复版")
    _log_window.write(f"超时保护: {CFG_TIMEOUT}秒 | 死循环检测: {CFG_MAX_REPEAT}次 | end等待: {CFG_END_WAIT}秒")
    _log_window.write("=" * 50)

    # 2. 主线程弹窗拿参数
    choose = ask_choice()
    times = ask_int("循环次数：", 0) if choose == "1" else 0
    luns = ask_int("多少轮一次结界突破：", 15) if choose == "1" else 0
    if choose == "1":
        global CFG_QW_TIMES
        CFG_QW_TIMES = ask_int("结界突破第9关释放qw轮数：", 8)
    ticket_num = ask_int("刷取次数：", 1) if choose in ("3", "6") else 0
    waste_time = ask_int("刷取时间(s)：", 60) if choose in ("3", "6") else 0
    yuhun_time = ask_int("刷取次数：", 1) if choose == "4" else 0
    if choose == "5":
        global AUTO_INTERVAL, AUTO_FLUCT
        AUTO_INTERVAL = ask_float("点击间隔(秒)：", 1.0)
        AUTO_FLUCT = ask_float("间隔上下波动幅度(秒)：", 0.5)

    # 3. 子线程跑业务
    def worker():
        try:
            emu = EmulatorWindow()
            bot = ImageBot(emu, "images")
            _log_window.write("[+] 初始化完成，开始运行...")

            if choose == "1":
                run_k28(bot, emu, times, luns)
            elif choose == "2":
                run_jj_tower(bot)
            elif choose == "3":
                run_pata(bot, ticket_num, waste_time)
            elif choose == "4":
                run_yuhun(bot, yuhun_time)
            elif choose == "5":
                run_autoclicker()
            elif choose == "6":
                run_tiaozhan(bot, ticket_num, waste_time)
            else:
                _log_window.write("无效选择")

        except Exception as e:
            error_msg = f"脚本异常：{e}"
            log(error_msg, False)
            _stop_flag.set()
            import traceback
            traceback.print_exc()

    t = threading.Thread(target=worker, daemon=True)
    t.start()

    # 4. 主线程事件循环
    _log_window.run()


if __name__ == "__main__":
    main()
