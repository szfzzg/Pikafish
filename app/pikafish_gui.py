#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Pikafish 象棋桌面应用（Tkinter）。

功能：
  * 人机对弈：玩家执红先行，引擎执黑
  * 局面分析：调用引擎 MultiPV 分析，显示评分与推荐着法
  * 着法记录、悔棋、重新开始

运行：
    python3 app/pikafish_gui.py

可选参数：
    --engine PATH    Pikafish 可执行文件路径
    --net PATH       NNUE 网络文件路径
"""

import argparse
import os
import queue
import subprocess
import threading
import tkinter as tk
from tkinter import font as tkfont
from tkinter import messagebox, ttk

# ---------------------------------------------------------------------------
# 常量与棋盘模型
# ---------------------------------------------------------------------------

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_ENGINE = os.path.join(REPO_ROOT, "src", "pikafish")
DEFAULT_NET = os.path.join(REPO_ROOT, "src", "pikafish.nnue")

START_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"

FILES = "abcdefghi"

PIECE_NAMES = {
    "K": "帅", "A": "仕", "B": "相", "N": "马", "R": "车", "C": "炮", "P": "兵",
    "k": "将", "a": "士", "b": "象", "n": "马", "r": "车", "c": "炮", "p": "卒",
}

ORTHO = ((1, 0), (-1, 0), (0, 1), (0, -1))
DIAG = ((1, 1), (1, -1), (-1, 1), (-1, -1))
HORSE = ((2, 1), (2, -1), (-2, 1), (-2, -1),
         (1, 2), (1, -2), (-1, 2), (-1, -2))

RED = "r"
BLACK = "b"


def in_board(r, c):
    return 0 <= r < 10 and 0 <= c < 9


def color_of(piece):
    return RED if piece.isupper() else BLACK


def move_to_uci(move):
    fr, fc, tr, tc = move
    return f"{FILES[fc]}{fr}{FILES[tc]}{tr}"


def uci_to_move(uci):
    if len(uci) < 4:
        raise ValueError(f"非法着法：{uci}")
    fc = FILES.index(uci[0])
    fr = int(uci[1])
    tc = FILES.index(uci[2])
    tr = int(uci[3])
    return (fr, fc, tr, tc)


class Board:
    """中国象棋棋盘：grid[0] 为红方底线（rank 0），grid[9] 为黑方底线。"""

    def __init__(self, fen=START_FEN):
        self.load_fen(fen)

    # -- FEN ---------------------------------------------------------------
    def load_fen(self, fen):
        fields = fen.split()
        rows = fields[0].split("/")
        if len(rows) != 10:
            raise ValueError("棋盘 FEN 行数不是 10")
        grid = []
        for row in reversed(rows):
            line = []
            for ch in row:
                if ch.isdigit():
                    line.extend(["."] * int(ch))
                else:
                    line.append(ch)
            if len(line) != 9:
                raise ValueError("棋盘 FEN 列数不是 9")
            grid.append(line)
        self.grid = grid
        self.side = RED if fields[1] == "w" else BLACK
        if len(fields) >= 6:
            self.halfmove = int(fields[4])
            self.fullmove = int(fields[5])
        elif len(fields) >= 4:
            self.halfmove = int(fields[2])
            self.fullmove = int(fields[3])
        else:
            self.halfmove = 0
            self.fullmove = 1

    def fen(self):
        rows = []
        for r in range(9, -1, -1):
            out = []
            empty = 0
            for ch in self.grid[r]:
                if ch == ".":
                    empty += 1
                else:
                    if empty:
                        out.append(str(empty))
                        empty = 0
                    out.append(ch)
            if empty:
                out.append(str(empty))
            rows.append("".join(out))
        side = "w" if self.side == RED else "b"
        return f"{'/'.join(rows)} {side} - - {self.halfmove} {self.fullmove}"

    def clone(self):
        b = Board.__new__(Board)
        b.grid = [row[:] for row in self.grid]
        b.side = self.side
        b.halfmove = self.halfmove
        b.fullmove = self.fullmove
        return b

    # -- 走子 --------------------------------------------------------------
    def apply_move(self, move):
        fr, fc, tr, tc = move
        piece = self.grid[fr][fc]
        captured = self.grid[tr][tc]
        if piece == ".":
            raise ValueError("起点没有棋子")
        self.grid[tr][tc] = piece
        self.grid[fr][fc] = "."
        if piece.upper() == "P" or captured != ".":
            self.halfmove = 0
        else:
            self.halfmove += 1
        if self.side == BLACK:
            self.fullmove += 1
        self.side = BLACK if self.side == RED else RED
        return captured

    # -- 着法生成 ----------------------------------------------------------
    @staticmethod
    def _in_palace(color, r, c):
        if not 3 <= c <= 5:
            return False
        if color == RED:
            return 0 <= r <= 2
        return 7 <= r <= 9

    def pseudo_moves_from(self, r, c):
        piece = self.grid[r][c]
        if piece == ".":
            return []
        color = color_of(piece)
        kind = piece.upper()
        moves = []

        def add(tr, tc):
            if not in_board(tr, tc):
                return
            target = self.grid[tr][tc]
            if target == "." or color_of(target) != color:
                moves.append((tr, tc))

        if kind == "K":
            for dr, dc in ORTHO:
                tr, tc = r + dr, c + dc
                if self._in_palace(color, tr, tc):
                    add(tr, tc)

        elif kind == "A":
            for dr, dc in DIAG:
                tr, tc = r + dr, c + dc
                if self._in_palace(color, tr, tc):
                    add(tr, tc)

        elif kind == "B":
            for dr, dc in DIAG:
                tr, tc = r + 2 * dr, c + 2 * dc
                if not in_board(tr, tc):
                    continue
                if color == RED and tr > 4:
                    continue
                if color == BLACK and tr < 5:
                    continue
                if self.grid[r + dr][c + dc] != ".":
                    continue
                add(tr, tc)

        elif kind == "N":
            for dr, dc in HORSE:
                tr, tc = r + dr, c + dc
                if not in_board(tr, tc):
                    continue
                if abs(dr) == 2:
                    lr, lc = r + dr // 2, c
                else:
                    lr, lc = r, c + dc // 2
                if self.grid[lr][lc] != ".":
                    continue
                add(tr, tc)

        elif kind == "R":
            for dr, dc in ORTHO:
                tr, tc = r + dr, c + dc
                while in_board(tr, tc):
                    target = self.grid[tr][tc]
                    if target == ".":
                        moves.append((tr, tc))
                    else:
                        if color_of(target) != color:
                            moves.append((tr, tc))
                        break
                    tr += dr
                    tc += dc

        elif kind == "C":
            for dr, dc in ORTHO:
                tr, tc = r + dr, c + dc
                while in_board(tr, tc) and self.grid[tr][tc] == ".":
                    moves.append((tr, tc))
                    tr += dr
                    tc += dc
                if not in_board(tr, tc):
                    continue
                # 跳过炮架，寻找可以吃掉的敌子
                tr += dr
                tc += dc
                while in_board(tr, tc):
                    target = self.grid[tr][tc]
                    if target != ".":
                        if color_of(target) != color:
                            moves.append((tr, tc))
                        break
                    tr += dr
                    tc += dc

        elif kind == "P":
            forward = 1 if color == RED else -1
            add(r + forward, c)
            if (color == RED and r >= 5) or (color == BLACK and r <= 4):
                add(r, c - 1)
                add(r, c + 1)

        return moves

    def find_king(self, color):
        king = "K" if color == RED else "k"
        for r in range(10):
            for c in range(9):
                if self.grid[r][c] == king:
                    return (r, c)
        return None

    def kings_face(self):
        rk = self.find_king(RED)
        bk = self.find_king(BLACK)
        if not rk or not bk or rk[1] != bk[1]:
            return False
        c = rk[1]
        low, high = sorted((rk[0], bk[0]))
        return all(self.grid[r][c] == "." for r in range(low + 1, high))

    def is_in_check(self, color=None):
        color = color or self.side
        king = self.find_king(color)
        if king is None:
            return True
        enemy = BLACK if color == RED else RED
        for r in range(10):
            for c in range(9):
                piece = self.grid[r][c]
                if piece == "." or color_of(piece) != enemy:
                    continue
                if king in self.pseudo_moves_from(r, c):
                    return True
        return self.kings_face()

    def legal_moves(self, color=None):
        color = color or self.side
        result = []
        for r in range(10):
            for c in range(9):
                piece = self.grid[r][c]
                if piece == "." or color_of(piece) != color:
                    continue
                for tr, tc in self.pseudo_moves_from(r, c):
                    nxt = self.clone()
                    nxt.apply_move((r, c, tr, tc))
                    if not nxt.is_in_check(color):
                        result.append((r, c, tr, tc))
        return result


# ---------------------------------------------------------------------------
# 引擎通信
# ---------------------------------------------------------------------------

class EngineError(Exception):
    pass


def parse_info(line):
    """解析 UCI 的 info 行，返回 {depth, multipv, score, pv} 或 None。"""
    tokens = line.split()
    if "depth" not in tokens or "score" not in tokens:
        return None
    try:
        depth = int(tokens[tokens.index("depth") + 1])
    except (ValueError, IndexError):
        return None
    multipv = 1
    if "multipv" in tokens:
        try:
            multipv = int(tokens[tokens.index("multipv") + 1])
        except (ValueError, IndexError):
            pass
    score = None
    i = tokens.index("score")
    if i + 2 < len(tokens):
        try:
            score = (tokens[i + 1], int(tokens[i + 2]))
        except ValueError:
            score = None
    if score is None:
        return None
    pv = []
    if "pv" in tokens:
        pv = tokens[tokens.index("pv") + 1:]
    return {"depth": depth, "multipv": multipv, "score": score, "pv": pv}


class PikafishEngine:
    """在后台线程中驱动 Pikafish 的 UCI 协议。"""

    def __init__(self, engine_path, net_path):
        self.engine_path = os.path.abspath(engine_path)
        self.net_path = os.path.abspath(net_path)
        self._proc = None
        self._lines = queue.Queue()
        self._requests = queue.Queue()
        self._recent = []
        self._on_ready = None
        self._on_error = None
        self._thread = threading.Thread(target=self._main, daemon=True)

    def start(self, on_ready, on_error):
        self._on_ready = on_ready
        self._on_error = on_error
        self._thread.start()

    def submit_new_game(self):
        self._requests.put({"kind": "newgame"})

    def request_move(self, fen, movetime_ms, on_info, on_done, on_error):
        self._requests.put({
            "kind": "move", "fen": fen, "movetime": movetime_ms,
            "on_info": on_info, "on_done": on_done, "on_error": on_error,
        })

    def request_analysis(self, fen, movetime_ms, multipv, on_info, on_done, on_error):
        self._requests.put({
            "kind": "analysis", "fen": fen, "movetime": movetime_ms,
            "multipv": multipv, "on_info": on_info,
            "on_done": on_done, "on_error": on_error,
        })

    def shutdown(self):
        self._requests.put(None)
        if self._thread.is_alive():
            self._thread.join(timeout=1.5)
        if self._proc and self._proc.poll() is None:
            self._terminate()

    # -- 内部实现 ----------------------------------------------------------
    @staticmethod
    def _notify(callback, *args):
        if callback is None:
            return
        try:
            callback(*args)
        except Exception:
            pass

    def _read_stdout(self):
        try:
            for raw in self._proc.stdout:
                line = raw.rstrip("\r\n")
                if line.startswith("info string"):
                    self._recent.append(line)
                    self._recent = self._recent[-8:]
                self._lines.put(line)
        finally:
            self._lines.put(None)

    def _readline(self, timeout):
        try:
            line = self._lines.get(timeout=timeout)
        except queue.Empty:
            raise EngineError("等待引擎响应超时。")
        if line is None:
            detail = "\n".join(self._recent)
            msg = "引擎进程已退出。"
            if detail:
                msg += f"\n{detail}"
            raise EngineError(msg)
        return line

    def _send(self, command):
        if not self._proc or not self._proc.stdin:
            raise EngineError("引擎未启动。")
        try:
            self._proc.stdin.write(command + "\n")
            self._proc.stdin.flush()
        except (BrokenPipeError, OSError):
            raise EngineError("无法向引擎写入命令，进程可能已退出。")

    def _handshake(self):
        self._send("uci")
        while True:
            if self._readline(20) == "uciok":
                break
        self._send(f"setoption name EvalFile value {self.net_path}")
        self._send("setoption name Threads value 2")
        self._send("setoption name Hash value 128")
        self._send("isready")
        while True:
            if self._readline(30) == "readyok":
                break

    def _main(self):
        try:
            self._proc = subprocess.Popen(
                [self.engine_path],
                cwd=os.path.dirname(self.engine_path) or ".",
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
            )
        except OSError as exc:
            self._notify(self._on_error, f"无法启动引擎：{exc}")
            return

        threading.Thread(target=self._read_stdout, daemon=True).start()

        try:
            self._handshake()
        except EngineError as exc:
            self._notify(self._on_error, str(exc))
            self._terminate()
            return

        self._notify(self._on_ready)

        while True:
            req = self._requests.get()
            if req is None:
                break
            try:
                self._serve(req)
            except EngineError as exc:
                self._notify(req.get("on_error") or self._on_error, str(exc))
                self._terminate()
                return

        if self._proc and self._proc.poll() is None:
            try:
                self._send("quit")
                self._proc.wait(timeout=1.0)
            except (EngineError, subprocess.TimeoutExpired):
                self._terminate()

    def _serve(self, req):
        if req["kind"] == "newgame":
            self._send("ucinewgame")
            self._send("isready")
            while self._readline(10) != "readyok":
                pass
            return

        analysis = req["kind"] == "analysis"
        multipv = req.get("multipv", 3) if analysis else 1
        movetime = req["movetime"]
        self._send(f"setoption name MultiPV value {multipv}")
        self._send(f"position fen {req['fen']}")
        self._send(f"go movetime {movetime}")

        timeout = movetime / 1000.0 + 20
        results = {}
        last_depth = -1
        while True:
            line = self._readline(timeout)
            if line.startswith("info "):
                info = parse_info(line)
                if not info:
                    continue
                if analysis:
                    results[info["multipv"]] = info
                    if info["multipv"] == 1 and info["depth"] != last_depth:
                        last_depth = info["depth"]
                        self._notify(req["on_info"], info)
                elif info["depth"] != last_depth:
                    last_depth = info["depth"]
                    self._notify(req["on_info"], info)
            elif line.startswith("bestmove"):
                parts = line.split()
                best = parts[1] if len(parts) > 1 else "(none)"
                if analysis:
                    self._notify(req["on_done"], [results[k] for k in sorted(results)])
                else:
                    self._notify(req["on_done"], best)
                return

    def _terminate(self):
        if not self._proc:
            return
        if self._proc.poll() is None:
            self._proc.terminate()
            try:
                self._proc.wait(timeout=1.0)
            except subprocess.TimeoutExpired:
                self._proc.kill()


# ---------------------------------------------------------------------------
# 图形界面
# ---------------------------------------------------------------------------

CELL = 62
MARGIN_X = 44
MARGIN_Y = 42
R_PIECE = 25
CANVAS_W = MARGIN_X * 2 + CELL * 8
CANVAS_H = MARGIN_Y * 2 + CELL * 9

DIFFICULTIES = [
    ("快速（0.5 秒）", 500),
    ("普通（1.5 秒）", 1500),
    ("较强（3 秒）", 3000),
    ("最强（5 秒）", 5000),
]


def bx(c):
    return MARGIN_X + c * CELL


def by(r):
    return MARGIN_Y + (9 - r) * CELL


class PikafishApp:
    def __init__(self, root, engine_path, net_path):
        self.root = root
        self.engine_path = engine_path
        self.net_path = net_path

        self.board = Board()
        self.history = []
        self.selected = None
        self.legal_for_selected = []
        self.last_move = None
        self.game_over = False
        self.result_text = ""
        self.busy = False
        self.engine_ready = False
        self.engine_failed = False
        self.human = RED
        self.events = queue.Queue()
        self.engine = None

        self.font_family = self._pick_font()
        self._build_ui()
        self._render()
        self._start_engine()
        self._pump_events()

    # -- 初始化 ------------------------------------------------------------
    def _pick_font(self):
        families = set(tkfont.families(self.root))
        for name in ("Noto Sans CJK SC", "Noto Sans CJK JP", "WenQuanYi Micro Hei",
                     "Microsoft YaHei", "PingFang SC", "SimHei", "DejaVu Sans"):
            if name in families:
                return name
        return "TkDefaultFont"

    def _build_ui(self):
        self.root.title("Pikafish 象棋")
        self.root.resizable(False, False)
        self.root.configure(bg="#ece3d2")
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

        main = ttk.Frame(self.root, padding=10)
        main.pack(fill="both", expand=True)
        main.rowconfigure(0, weight=1)
        main.columnconfigure(0, weight=1)

        board_frame = tk.Frame(main, bg="#e8d3a9", highlightthickness=1,
                               highlightbackground="#7a4a23", bd=0)
        board_frame.grid(row=0, column=0, sticky="n")
        self.canvas = tk.Canvas(board_frame, width=CANVAS_W, height=CANVAS_H,
                                bg="#f3dfb6", highlightthickness=0)
        self.canvas.pack()
        self.canvas.bind("<Button-1>", self._on_canvas_click)

        side = ttk.Frame(main, padding=(14, 0, 0, 0))
        side.grid(row=0, column=1, sticky="nsew")
        side.columnconfigure(0, weight=1)
        side.rowconfigure(4, weight=1)

        header = ttk.Label(side, text="Pikafish 象棋",
                           font=(self.font_family, 16, "bold"))
        header.grid(row=0, column=0, sticky="w")

        self.status_var = tk.StringVar(value="正在启动引擎…")
        status = ttk.Label(side, textvariable=self.status_var, wraplength=330,
                           font=(self.font_family, 11, "bold"), foreground="#7a4a23")
        status.grid(row=1, column=0, sticky="ew", pady=(6, 10))

        buttons = ttk.Frame(side)
        buttons.grid(row=2, column=0, sticky="ew")
        self.btn_new = ttk.Button(buttons, text="新局", command=self._on_new_game)
        self.btn_undo = ttk.Button(buttons, text="悔棋", command=self._on_undo)
        self.btn_analyze = ttk.Button(buttons, text="分析局面", command=self._on_analyze)
        for i, btn in enumerate((self.btn_new, self.btn_undo, self.btn_analyze)):
            buttons.columnconfigure(i, weight=1)
            btn.grid(row=0, column=i, sticky="ew", padx=(0 if i == 0 else 4, 0))

        strength = ttk.Frame(side)
        strength.grid(row=3, column=0, sticky="ew", pady=(10, 8))
        strength.columnconfigure(1, weight=1)
        ttk.Label(strength, text="思考时间：", font=(self.font_family, 10)).grid(
            row=0, column=0, sticky="w")
        self.difficulty = ttk.Combobox(
            strength, values=[name for name, _ in DIFFICULTIES],
            state="readonly", width=16, font=(self.font_family, 10))
        self.difficulty.current(1)
        self.difficulty.grid(row=0, column=1, sticky="ew")

        notebook = ttk.Notebook(side)
        notebook.grid(row=4, column=0, sticky="nsew")
        self.notebook = notebook

        moves_tab = ttk.Frame(notebook, padding=4)
        moves_tab.rowconfigure(0, weight=1)
        moves_tab.columnconfigure(0, weight=1)
        self.moves_text = tk.Text(moves_tab, width=34, height=16, wrap="word",
                                  state="disabled", font=(self.font_family, 11),
                                  bg="#fffdf7", relief="flat", padx=6, pady=6)
        moves_sb = ttk.Scrollbar(moves_tab, orient="vertical",
                                 command=self.moves_text.yview)
        self.moves_text.configure(yscrollcommand=moves_sb.set)
        self.moves_text.grid(row=0, column=0, sticky="nsew")
        moves_sb.grid(row=0, column=1, sticky="ns")
        notebook.add(moves_tab, text="着法记录")

        analysis_tab = ttk.Frame(notebook, padding=4)
        analysis_tab.rowconfigure(0, weight=1)
        analysis_tab.columnconfigure(0, weight=1)
        self.analysis_text = tk.Text(analysis_tab, width=34, height=16, wrap="word",
                                     state="disabled", font=(self.font_family, 11),
                                     bg="#fffdf7", relief="flat", padx=6, pady=6)
        analysis_sb = ttk.Scrollbar(analysis_tab, orient="vertical",
                                    command=self.analysis_text.yview)
        self.analysis_text.configure(yscrollcommand=analysis_sb.set)
        self.analysis_text.grid(row=0, column=0, sticky="nsew")
        analysis_sb.grid(row=0, column=1, sticky="ns")
        notebook.add(analysis_tab, text="局面分析")
        self.analysis_tab = analysis_tab

        self.root.bind("<Control-n>", lambda _e: self._on_new_game())
        self.root.bind("<Control-z>", lambda _e: self._on_undo())
        self.root.bind("<Control-a>", lambda _e: self._on_analyze())

    def _start_engine(self):
        if not os.path.isfile(self.engine_path):
            self._fatal(
                f"找不到引擎：{self.engine_path}\n\n"
                "请先在 src 目录执行：\n    make -j build ARCH=x86-64")
            return
        if not os.path.isfile(self.net_path):
            self._fatal(
                f"找不到 NNUE 网络文件：{self.net_path}\n\n"
                "请从 Pikafish 官方仓库下载 pikafish.nnue 并放入 src 目录。")
            return

        self.engine = PikafishEngine(self.engine_path, self.net_path)
        self.engine.start(
            lambda: self.events.put(("ready", None)),
            lambda msg: self.events.put(("engine_error", msg)),
        )

    def _fatal(self, message):
        self.engine_failed = True
        self._refresh_controls()
        self.status_var.set("引擎不可用")
        messagebox.showerror("启动失败", message)

    # -- 事件循环 ----------------------------------------------------------
    def _pump_events(self):
        while True:
            try:
                kind, payload = self.events.get_nowait()
            except queue.Empty:
                break
            if kind == "ready":
                self.engine_ready = True
                self.status_var.set("轮到你走棋（红方）")
                self._refresh_controls()
            elif kind == "engine_error":
                self._on_engine_error(payload)
            elif kind == "thinking":
                self._on_thinking(payload)
            elif kind == "bestmove":
                self._on_bestmove(payload)
            elif kind == "analysis_progress":
                self._on_analysis_progress(payload)
            elif kind == "analysis_done":
                self._on_analysis_done(*payload)
            elif kind == "analysis_error":
                self._on_analysis_error(payload)
        self.root.after(60, self._pump_events)

    def _on_engine_error(self, message):
        self.busy = False
        self.engine_ready = False
        self.engine_failed = True
        self.status_var.set("引擎不可用")
        self._set_text(self.analysis_text, f"引擎错误：\n{message}")
        self._refresh_controls()
        messagebox.showerror("引擎错误", message)

    def _on_thinking(self, info):
        if not self.busy:
            return
        score = self._score_text(info, self.board.side)
        self.status_var.set(f"引擎思考中… 深度 {info['depth']}  评分 {score}")

    def _on_bestmove(self, best):
        self.busy = False
        if self.game_over:
            self._refresh_controls()
            return
        if best in ("(none)", "0000"):
            if not self._check_game_over():
                self._on_engine_error("引擎没有返回可走的着法。")
            return
        try:
            move = uci_to_move(best)
        except ValueError:
            self._on_engine_error(f"无法解析引擎着法：{best}")
            return
        if move not in self.board.legal_moves():
            self._on_engine_error(f"引擎返回了非法着法：{best}")
            return
        self._record_move(move)
        self._render()
        if self._check_game_over():
            return
        self.status_var.set("轮到你走棋（红方）")
        self._refresh_controls()

    def _on_analysis_progress(self, info):
        if not self.busy:
            return
        self._set_text(self.analysis_text,
                       f"分析中…\n深度 {info['depth']}  评分 "
                       f"{self._score_text(info, self.board.side)}")

    def _on_analysis_done(self, fen, lines):
        self.busy = False
        self._refresh_controls()
        if not lines:
            self._set_text(self.analysis_text, "没有得到分析结果。")
        else:
            self._set_text(self.analysis_text, self._format_analysis(fen, lines))
        if not self.game_over:
            self.status_var.set(self._turn_status())

    def _on_analysis_error(self, message):
        self.busy = False
        self._refresh_controls()
        self._set_text(self.analysis_text, f"分析失败：\n{message}")
        messagebox.showerror("分析失败", message)

    # -- 游戏流程 ----------------------------------------------------------
    def _on_new_game(self):
        if self.busy:
            return
        self.board = Board()
        self.history = []
        self.selected = None
        self.legal_for_selected = []
        self.last_move = None
        self.game_over = False
        self.result_text = ""
        self._rebuild_move_list()
        self._set_text(self.analysis_text, "")
        self._render()
        self.status_var.set(self._turn_status())
        self._refresh_controls()
        if self.engine_ready:
            self.engine.submit_new_game()

    def _on_undo(self):
        if self.busy or not self.history:
            return
        count = 1 if self.history[-1]["side"] == self.human else min(2, len(self.history))
        entry = None
        for _ in range(count):
            entry = self.history.pop()
        self.board = Board(entry["fen_before"])
        self.game_over = False
        self.result_text = ""
        self.selected = None
        self.legal_for_selected = []
        self.last_move = self.history[-1]["move"] if self.history else None
        self._rebuild_move_list()
        self._render()
        self._set_text(self.analysis_text, "")
        self.status_var.set("已悔棋。轮到你走棋（红方）")
        self._refresh_controls()

    def _on_analyze(self):
        if self.busy or not self.engine_ready:
            return
        self.busy = True
        self.selected = None
        self.legal_for_selected = []
        self._render()
        self._refresh_controls()
        self.notebook.select(self.analysis_tab)
        self._set_text(self.analysis_text, "分析中…")
        fen = self.board.fen()
        self.engine.request_analysis(
            fen, 2500, 3,
            lambda info: self.events.put(("analysis_progress", info)),
            lambda lines: self.events.put(("analysis_done", (fen, lines))),
            lambda msg: self.events.put(("analysis_error", msg)),
        )

    def _on_canvas_click(self, event):
        if not self.engine_ready or self.busy or self.game_over:
            return
        if self.board.side != self.human:
            return
        square = self._event_square(event.x, event.y)
        if square is None:
            return
        r, c = square
        piece = self.board.grid[r][c]

        if piece != "." and color_of(piece) == self.human:
            self.selected = (r, c)
            self.legal_for_selected = [
                mv for mv in self.board.legal_moves()
                if (mv[0], mv[1]) == (r, c)
            ]
            self._render()
            return

        if self.selected:
            move = (self.selected[0], self.selected[1], r, c)
            if move in self.legal_for_selected:
                self.selected = None
                self.legal_for_selected = []
                self._do_human_move(move)
                return
            self.selected = None
            self.legal_for_selected = []
            self._render()

    def _do_human_move(self, move):
        self._record_move(move)
        self._render()
        if self._check_game_over():
            return
        self._start_engine_move()

    def _start_engine_move(self):
        if not self.engine_ready:
            return
        self.busy = True
        self.selected = None
        self.legal_for_selected = []
        self._render()
        self._refresh_controls()
        self.status_var.set("引擎思考中…")
        self.engine.request_move(
            self.board.fen(), self._movetime(),
            lambda info: self.events.put(("thinking", info)),
            lambda best: self.events.put(("bestmove", best)),
            lambda msg: self.events.put(("engine_error", msg)),
        )

    def _check_game_over(self):
        side = self.board.side
        if self.board.legal_moves():
            return False
        in_check = self.board.is_in_check(side)
        loser = "红方" if side == RED else "黑方"
        winner = "黑方" if side == RED else "红方"
        reason = "被将死" if in_check else "无子可动"
        self.game_over = True
        self.result_text = f"{winner}胜（{loser}{reason}）"
        self.busy = False
        self.status_var.set(self.result_text)
        self._refresh_controls()
        return True

    def _record_move(self, move):
        fr, fc, tr, tc = move
        side = self.board.side
        piece = self.board.grid[fr][fc]
        fen_before = self.board.fen()
        captured = self.board.apply_move(move)
        self.history.append({
            "uci": move_to_uci(move),
            "piece": piece,
            "captured": captured,
            "side": side,
            "fen_before": fen_before,
            "move": move,
        })
        self.last_move = move
        self._rebuild_move_list()

    # -- 界面辅助 ----------------------------------------------------------
    def _movetime(self):
        index = self.difficulty.current()
        if index < 0:
            index = 1
        return DIFFICULTIES[index][1]

    def _turn_status(self):
        if self.game_over:
            return self.result_text
        if not self.engine_ready:
            return "引擎不可用" if self.engine_failed else "正在启动引擎…"
        return "轮到你走棋（红方）" if self.board.side == self.human else "引擎思考中…"

    def _refresh_controls(self):
        idle = not self.busy
        self.btn_new.configure(state="normal" if idle else "disabled")
        self.btn_undo.configure(
            state="normal" if idle and self.history else "disabled")
        self.btn_analyze.configure(
            state="normal" if idle and self.engine_ready else "disabled")
        self.difficulty.configure(
            state="readonly" if self.engine_ready and not self.engine_failed
            else "disabled")

    def _set_text(self, widget, text):
        widget.configure(state="normal")
        widget.delete("1.0", "end")
        widget.insert("1.0", text)
        widget.configure(state="disabled")

    def _rebuild_move_list(self):
        lines = []
        for i, entry in enumerate(self.history):
            number = i // 2 + 1
            side = "红" if entry["side"] == RED else "黑"
            name = PIECE_NAMES.get(entry["piece"], entry["piece"])
            uci = entry["uci"]
            prefix = f"{number:>3}." if entry["side"] == RED else f"{number:>3}…"
            lines.append(f"{prefix} {side}方 {name} {uci[:2]}-{uci[2:]}")
        self._set_text(self.moves_text, "\n".join(lines))
        self.moves_text.see("end")

    def _format_analysis(self, fen, lines):
        board = Board(fen)
        side = board.side
        who = "红方" if side == RED else "黑方"
        depth = max(info["depth"] for info in lines)
        out = [f"{who}走棋 | 分析深度 {depth}", ""]
        for info in lines:
            out.append(f"{info['multipv']}. 评分 {self._score_text(info, side)}"
                       f"   深度 {info['depth']}")
            pv_text = self._format_pv(board, info["pv"])
            if pv_text:
                out.append(f"   {pv_text}")
            out.append("")
        out.append("评分以红方视角显示")
        return "\n".join(out)

    def _format_pv(self, board, pv):
        b = board.clone()
        parts = []
        for uci in pv[:10]:
            try:
                move = uci_to_move(uci)
            except ValueError:
                break
            piece = b.grid[move[0]][move[1]]
            if piece == ".":
                break
            name = PIECE_NAMES.get(piece, piece)
            parts.append(f"{name} {uci[:2]}-{uci[2:]}")
            b.apply_move(move)
        return "　".join(parts)

    @staticmethod
    def _score_text(info, side):
        score = info.get("score")
        if not score:
            return "—"
        kind, value = score
        if kind == "mate":
            n = value if side == RED else -value
            if n > 0:
                return f"红方 {n} 步杀"
            if n < 0:
                return f"黑方 {-n} 步杀"
            return "杀棋"
        cp = value if side == RED else -value
        return f"{cp / 100:+.2f}"

    def _event_square(self, x, y):
        c = round((x - MARGIN_X) / CELL)
        r = 9 - round((y - MARGIN_Y) / CELL)
        if not in_board(r, c):
            return None
        if abs(x - bx(c)) > CELL * 0.48 or abs(y - by(r)) > CELL * 0.48:
            return None
        return (r, c)

    # -- 绘制 --------------------------------------------------------------
    def _render(self):
        cv = self.canvas
        cv.delete("all")
        self._draw_board_background()

        if self.last_move:
            for r, c in (self.last_move[:2], self.last_move[2:]):
                x, y = bx(c), by(r)
                cv.create_rectangle(
                    x - CELL / 2 + 6, y - CELL / 2 + 6,
                    x + CELL / 2 - 6, y + CELL / 2 - 6,
                    fill="#f2c14e", stipple="gray25", outline="")

        self._draw_grid()
        self._draw_position_marks()
        self._draw_pieces()

        if self.board.is_in_check():
            king = self.board.find_king(self.board.side)
            if king:
                x, y = bx(king[1]), by(king[0])
                cv.create_oval(x - R_PIECE - 7, y - R_PIECE - 7,
                               x + R_PIECE + 7, y + R_PIECE + 7,
                               outline="#e74c3c", width=3)

        if self.selected:
            r, c = self.selected
            x, y = bx(c), by(r)
            cv.create_oval(x - R_PIECE - 5, y - R_PIECE - 5,
                           x + R_PIECE + 5, y + R_PIECE + 5,
                           outline="#e67e22", width=3)

        for _fr, _fc, tr, tc in self.legal_for_selected:
            x, y = bx(tc), by(tr)
            if self.board.grid[tr][tc] == ".":
                cv.create_oval(x - 7, y - 7, x + 7, y + 7,
                               fill="#2e8b57", outline="")
            else:
                cv.create_oval(x - R_PIECE - 4, y - R_PIECE - 4,
                               x + R_PIECE + 4, y + R_PIECE + 4,
                               outline="#2e8b57", width=3)

    def _draw_board_background(self):
        cv = self.canvas
        x0, y0 = bx(0) - 14, by(9) - 14
        x1, y1 = bx(8) + 14, by(0) + 14
        cv.create_rectangle(x0, y0, x1, y1, outline="#7a4a23", width=3)
        cv.create_rectangle(x0 + 6, y0 + 6, x1 - 6, y1 - 6,
                            outline="#b98a5c", width=1)

        river_y = (by(4) + by(5)) / 2
        cv.create_text(bx(2), river_y, text="楚  河", fill="#9a6a3a",
                       font=(self.font_family, 17, "bold"))
        cv.create_text(bx(6), river_y, text="汉  界", fill="#9a6a3a",
                       font=(self.font_family, 17, "bold"))

        for c in range(9):
            cv.create_text(bx(c), by(0) + 28, text=FILES[c], fill="#b98a5c",
                           font=(self.font_family, 9))
        for r in range(10):
            cv.create_text(bx(0) - 28, by(r), text=str(r), fill="#b98a5c",
                           font=(self.font_family, 9))

    def _draw_grid(self):
        cv = self.canvas
        for r in range(10):
            cv.create_line(bx(0), by(r), bx(8), by(r),
                           fill="#8a5b34", width=1)
        for c in range(9):
            if c in (0, 8):
                cv.create_line(bx(c), by(0), bx(c), by(9),
                               fill="#8a5b34", width=1)
            else:
                cv.create_line(bx(c), by(0), bx(c), by(4),
                               fill="#8a5b34", width=1)
                cv.create_line(bx(c), by(5), bx(c), by(9),
                               fill="#8a5b34", width=1)

        cv.create_line(bx(3), by(0), bx(5), by(2), fill="#8a5b34")
        cv.create_line(bx(5), by(0), bx(3), by(2), fill="#8a5b34")
        cv.create_line(bx(3), by(7), bx(5), by(9), fill="#8a5b34")
        cv.create_line(bx(5), by(7), bx(3), by(9), fill="#8a5b34")

    def _draw_position_marks(self):
        cv = self.canvas
        marks = [(2, 1), (2, 7), (7, 1), (7, 7),
                 (3, 0), (3, 2), (3, 4), (3, 6), (3, 8),
                 (6, 0), (6, 2), (6, 4), (6, 6), (6, 8)]
        for r, c in marks:
            x, y = bx(c), by(r)
            for dx in (-1, 1):
                for dy in (-1, 1):
                    if (dx < 0 and c == 0) or (dx > 0 and c == 8):
                        continue
                    cv.create_line(x + dx * 6, y + dy * 6,
                                   x + dx * 12, y + dy * 6,
                                   fill="#a0714a", width=1)
                    cv.create_line(x + dx * 6, y + dy * 6,
                                   x + dx * 6, y + dy * 12,
                                   fill="#a0714a", width=1)

    def _draw_pieces(self):
        cv = self.canvas
        for r in range(10):
            for c in range(9):
                piece = self.board.grid[r][c]
                if piece == ".":
                    continue
                x, y = bx(c), by(r)
                cv.create_oval(x - R_PIECE + 2, y - R_PIECE + 3,
                               x + R_PIECE + 2, y + R_PIECE + 3,
                               fill="#d8c29a", outline="")
                cv.create_oval(x - R_PIECE, y - R_PIECE,
                               x + R_PIECE, y + R_PIECE,
                               fill="#fdf3dc", outline="#9a6a3a", width=2)
                cv.create_oval(x - R_PIECE + 4, y - R_PIECE + 4,
                               x + R_PIECE - 4, y + R_PIECE - 4,
                               outline="#c9a06b", width=1)
                color = "#b52d1f" if piece.isupper() else "#1b1b1b"
                cv.create_text(x, y + 1, text=PIECE_NAMES.get(piece, piece),
                               fill=color,
                               font=(self.font_family, 20, "bold"))

    # -- 退出 --------------------------------------------------------------
    def _on_close(self):
        if self.engine:
            self.engine.shutdown()
        self.root.destroy()


def main():
    parser = argparse.ArgumentParser(description="Pikafish 象棋桌面应用")
    parser.add_argument("--engine", default=DEFAULT_ENGINE,
                        help="Pikafish 可执行文件路径")
    parser.add_argument("--net", default=DEFAULT_NET,
                        help="NNUE 网络文件路径")
    args = parser.parse_args()

    root = tk.Tk()
    PikafishApp(root, args.engine, args.net)
    root.mainloop()


if __name__ == "__main__":
    main()
