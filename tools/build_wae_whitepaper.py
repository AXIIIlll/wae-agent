# -*- coding: utf-8 -*-
"""生成 WAE V2.6 架構白皮書（可列印版 PPTX）。

設計原則：
  * 所有文字都是可編輯的文字框（不是圖片），方便列印與後續修改
  * 16:9、字級 >= 11pt、邊界留白足夠，列印成 A4 橫向可讀
  * 每個技術頁都有「原理 / 公式 / 程式設計巧思」三種元素之一
"""
import os
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR, MSO_AUTO_SIZE
from pptx.enum.shapes import MSO_SHAPE

# ── 設計 token ────────────────────────────────────────────────────────
C_SECTION = "WORD_SECTION"
C_TITLE_BG = RGBColor(0x0B, 0x1F, 0x33)      # 深海藍
C_SECTION_BG = RGBColor(0x0E, 0x2A, 0x3D)
C_ACCENT = RGBColor(0x14, 0xB8, 0xA6)         # 青綠
C_ACCENT2 = RGBColor(0xF5, 0x9E, 0x0B)        # 琥珀
C_INK = RGBColor(0x1E, 0x29, 0x3B)
C_BODY = RGBColor(0x33, 0x41, 0x55)
C_MUTED = RGBColor(0x64, 0x74, 0x8B)
C_LIGHT = RGBColor(0xF1, 0xF5, 0xF9)
C_CODE_BG = RGBColor(0x14, 0x1E, 0x2D)
C_CODE_FG = RGBColor(0xD6, 0xE2, 0xF0)
C_WHITE = RGBColor(0xFF, 0xFF, 0xFF)
C_PANEL = RGBColor(0xF8, 0xFA, 0xFC)

FONT = "Microsoft JhengHei"
MONO = "Consolas"

W, H = 13.333, 7.5
DECK_TITLE = "WAE V2.6 架構白皮書"

prs = Presentation()
prs.slide_width = Inches(W)
prs.slide_height = Inches(H)
BLANK = prs.slide_layouts[6]

_page = [0]


def _tb(slide, x, y, w, h, wrap=True):
    box = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = box.text_frame
    # 關閉自動調整：預設的 spAutoFit 會讓部分渲染器把文字框撐大，
    # 導致文字超出投影片邊界（實測 LibreOffice 會這樣）。固定寬高才能保證排版。
    tf.auto_size = MSO_AUTO_SIZE.NONE
    tf.word_wrap = wrap
    tf.margin_left = 0
    tf.margin_right = 0
    tf.margin_top = 0
    tf.margin_bottom = 0
    return tf


def _rect(slide, x, y, w, h, color, line=None):
    shp = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(x), Inches(y), Inches(w), Inches(h))
    shp.fill.solid()
    shp.fill.fore_color.rgb = color
    if line is None:
        shp.line.fill.background()
    else:
        shp.line.color.rgb = line
        shp.line.width = Pt(0.75)
    shp.shadow.inherit = False
    return shp


def _para(tf, text, size=14, color=C_BODY, bold=False, font=FONT, space_after=6,
          space_before=0, align=PP_ALIGN.LEFT, first=False, line_spacing=1.15, italic=False):
    p = tf.paragraphs[0] if first else tf.add_paragraph()
    p.alignment = align
    p.space_after = Pt(space_after)
    p.space_before = Pt(space_before)
    p.line_spacing = line_spacing
    r = p.add_run()
    r.text = text
    r.font.size = Pt(size)
    r.font.bold = bold
    r.font.italic = italic
    r.font.name = font
    r.font.color.rgb = color
    return p


def _footer(slide, tag=""):
    _page[0] += 1
    line = slide.shapes.add_connector(1, Inches(0.62), Inches(6.98), Inches(12.71), Inches(6.98))
    line.line.color.rgb = RGBColor(0xD5, 0xDD, 0xE5)
    line.line.width = Pt(0.75)
    tf = _tb(slide, 0.62, 7.04, 8.0, 0.3)
    left = DECK_TITLE if not tag else f"{DECK_TITLE}  ·  {tag}"
    _para(tf, left, size=9, color=C_MUTED, first=True, space_after=0)
    tf2 = _tb(slide, 11.6, 7.04, 1.1, 0.3)
    _para(tf2, f"{_page[0]:02d}", size=9, color=C_MUTED, first=True,
          space_after=0, align=PP_ALIGN.RIGHT)


def content_slide(title, kicker=None, tag=""):
    slide = prs.slides.add_slide(BLANK)
    _rect(slide, 0, 0, W, 1.12, C_TITLE_BG)
    _rect(slide, 0, 1.12, W, 0.055, C_ACCENT)
    tf = _tb(slide, 0.62, 0.19, 11.0, 0.6)
    _para(tf, title, size=26, color=C_WHITE, bold=True, first=True, space_after=0)
    if kicker:
        tf2 = _tb(slide, 0.64, 0.76, 11.9, 0.32)
        _para(tf2, kicker, size=11.5, color=RGBColor(0x93, 0xE2, 0xD8), first=True, space_after=0)
    _footer(slide, tag)
    return slide


def bullets(slide, items, x=0.62, y=1.46, w=12.1, h=5.2, size=14, gap=5):
    """items: str 或 (level, str) 或 ("H", str) 小標 或 ("CODE", str)"""
    tf = _tb(slide, x, y, w, h)
    first = True
    for it in items:
        if isinstance(it, tuple):
            kind, text = it
        else:
            kind, text = "B", it
        if kind == "H":
            _para(tf, text, size=size + 1.5, color=C_INK, bold=True, first=first,
                  space_before=6 if not first else 0, space_after=gap)
        elif kind == "S":
            _para(tf, text, size=size - 1.5, color=C_MUTED, first=first, space_after=gap)
        elif kind == "1":
            _para(tf, "• " + text, size=size, color=C_BODY, first=first, space_after=gap)
        elif kind == "2":
            _para(tf, "    – " + text, size=size - 1, color=C_MUTED, first=first, space_after=gap)
        elif kind == "CODE":
            _para(tf, text, size=size - 1.5, color=RGBColor(0x0F, 0x76, 0x6E),
                  font=MONO, first=first, space_after=gap)
        else:
            _para(tf, text, size=size, color=C_BODY, first=first, space_after=gap)
        first = False
    return tf


def panel(slide, x, y, w, h, fill=C_PANEL, border=RGBColor(0xE2, 0xE8, 0xF0)):
    return _rect(slide, x, y, w, h, fill, border)


def code_block(slide, lines, x=0.62, y=3.6, w=12.1, h=2.6, size=10.5, title=None):
    panel(slide, x, y, w, h, fill=C_CODE_BG)
    yy = y + 0.12
    if title:
        tf0 = _tb(slide, x + 0.18, yy, w - 0.36, 0.28)
        _para(tf0, title, size=9.5, color=RGBColor(0x7D, 0xD3, 0xFC), font=MONO, first=True, space_after=0)
        yy += 0.3
    tf = _tb(slide, x + 0.18, yy, w - 0.36, h - (yy - y) - 0.1)
    first = True
    for ln in lines:
        _para(tf, ln, size=size, color=C_CODE_FG, font=MONO, first=first, space_after=1.5, line_spacing=1.0)
        first = False
    return tf


def takeaway(slide, text, y=6.28):
    panel(slide, 0.62, y, 12.1, 0.56, fill=RGBColor(0xEC, 0xFD, 0xF5),
          border=RGBColor(0x99, 0xF6, 0xE4))
    _rect(slide, 0.62, y, 0.07, 0.56, C_ACCENT)
    tf = _tb(slide, 0.86, y + 0.14, 11.7, 0.34)
    _para(tf, text, size=11.5, color=RGBColor(0x0F, 0x51, 0x43), bold=True, first=True, space_after=0)


def formula_panel(slide, lines, x=0.62, y=1.5, w=6.0, h=2.4, size=13):
    panel(slide, x, y, w, h, fill=RGBColor(0xFF, 0xFB, 0xEB),
          border=RGBColor(0xFD, 0xE6, 0x8A))
    tf = _tb(slide, x + 0.22, y + 0.18, w - 0.44, h - 0.3)
    first = True
    for ln in lines:
        _para(tf, ln, size=size, color=RGBColor(0x78, 0x35, 0x0F), font=MONO,
              first=first, space_after=4, line_spacing=1.05)
        first = False


def table(slide, rows, x=0.62, y=1.5, w=12.1, col_widths=None, size=11, header_size=None,
          row_h=0.34, header_fill=C_TITLE_BG):
    nr, nc = len(rows), len(rows[0])
    shp = slide.shapes.add_table(nr, nc, Inches(x), Inches(y), Inches(w), Inches(row_h * nr))
    tbl = shp.table
    if col_widths:
        total = sum(col_widths)
        for i, cw in enumerate(col_widths):
            tbl.columns[i].width = Emu(int(Inches(w) * cw / total))
    for r in range(nr):
        tbl.rows[r].height = Inches(row_h)
        for c in range(nc):
            cell = tbl.cell(r, c)
            cell.margin_left = Inches(0.07)
            cell.margin_right = Inches(0.05)
            cell.margin_top = Inches(0.02)
            cell.margin_bottom = Inches(0.02)
            cell.vertical_anchor = MSO_ANCHOR.MIDDLE
            cell.fill.solid()
            if r == 0:
                cell.fill.fore_color.rgb = header_fill
            else:
                cell.fill.fore_color.rgb = C_WHITE if r % 2 else RGBColor(0xF6, 0xF9, 0xFC)
            tf = cell.text_frame
            tf.word_wrap = True
            p = tf.paragraphs[0]
            p.alignment = PP_ALIGN.LEFT
            run = p.add_run()
            run.text = str(rows[r][c])
            run.font.name = FONT
            run.font.size = Pt(header_size or size if r == 0 else size)
            run.font.bold = (r == 0)
            run.font.color.rgb = C_WHITE if r == 0 else C_BODY
    return tbl


def section_slide(num, title, en, points):
    slide = prs.slides.add_slide(BLANK)
    _rect(slide, 0, 0, W, H, C_SECTION_BG)
    _rect(slide, 0, 0, 0.16, H, C_ACCENT)
    tf = _tb(slide, 1.1, 2.0, 2.2, 1.6)
    _para(tf, num, size=76, color=RGBColor(0x1B, 0x4B, 0x5C), bold=True, font=MONO,
          first=True, space_after=0)
    tf2 = _tb(slide, 1.15, 3.35, 9.6, 0.9)
    _para(tf2, title, size=38, color=C_WHITE, bold=True, first=True, space_after=2)
    tf3 = _tb(slide, 1.18, 4.18, 10.0, 0.45)
    _para(tf3, en, size=13, color=RGBColor(0x93, 0xE2, 0xD8), font=MONO, first=True, space_after=0)
    tf4 = _tb(slide, 1.18, 4.85, 10.6, 1.6)
    first = True
    for p in points:
        _para(tf4, "· " + p, size=12.5, color=RGBColor(0xC7, 0xD6, 0xE2), first=first, space_after=5)
        first = False
    _page[0] += 1
    tf5 = _tb(slide, 11.6, 7.04, 1.1, 0.3)
    _para(tf5, f"{_page[0]:02d}", size=9, color=RGBColor(0x64, 0x74, 0x8B), first=True,
          space_after=0, align=PP_ALIGN.RIGHT)
    return slide


def layer_diagram(slide, layers, x=0.62, y=1.45, w=7.4):
    """layers: [(code, name, desc)] 由上往下"""
    top = y
    hh = 0.62
    gap = 0.1
    for i, (code, name, desc) in enumerate(layers):
        fill = C_TITLE_BG if i % 2 == 0 else RGBColor(0x14, 0x3A, 0x50)
        _rect(slide, x, top, w, hh, fill)
        _rect(slide, x, top, 0.09, hh, C_ACCENT if i % 3 != 2 else C_ACCENT2)
        tf = _tb(slide, x + 0.2, top + 0.08, 0.95, 0.45)
        _para(tf, code, size=13, color=C_ACCENT, bold=True, font=MONO, first=True, space_after=0)
        tf2 = _tb(slide, x + 1.15, top + 0.055, w - 1.3, 0.26)
        _para(tf2, name, size=12.5, color=C_WHITE, bold=True, first=True, space_after=0)
        tf3 = _tb(slide, x + 1.15, top + 0.315, w - 1.3, 0.26)
        _para(tf3, desc, size=9.5, color=RGBColor(0xA9, 0xBD, 0xCE), first=True, space_after=0)
        top += hh + gap


# ══════════════════════════════════════════════════════════════════════
# 01 封面
# ══════════════════════════════════════════════════════════════════════
s = prs.slides.add_slide(BLANK)
_rect(s, 0, 0, W, H, C_TITLE_BG)
_rect(s, 0, 0, 0.18, H, C_ACCENT)
_rect(s, 7.6, 0, 5.73, H, RGBColor(0x0E, 0x2A, 0x3D))
tf = _tb(s, 1.15, 1.75, 6.2, 0.4)
_para(tf, "ANDROID GUI AGENT  ·  技術架構白皮書", size=11.5,
      color=RGBColor(0x93, 0xE2, 0xD8), font=MONO, first=True, space_after=0)
tf = _tb(s, 1.15, 2.25, 6.3, 1.9)
_para(tf, "WAE V2.6", size=54, color=C_WHITE, bold=True, first=True, space_after=0)
_para(tf, "全管線架構白皮書", size=28, color=C_WHITE, bold=True, space_after=0)
tf = _tb(s, 1.15, 4.35, 6.1, 1.3)
_para(tf, "無障礙服務驅動 · 離線推論 · 資料驅動 · 可審計決策",
      size=13, color=RGBColor(0xC7, 0xD6, 0xE2), first=True, space_after=4)
_para(tf, "L0 直達 → L1 感知 → L1.5 語意 → L3 決策 → L4 執行 → L5 歸因",
      size=11, color=RGBColor(0x94, 0xA9, 0xBC), first=False, space_after=0)
tf = _tb(s, 1.15, 6.45, 6.1, 0.5)
_para(tf, "版本 2.6 ｜ 2026 年 11 月 ｜ 內部技術文件", size=10.5,
      color=RGBColor(0x64, 0x74, 0x8B), first=True, space_after=0)
# 封面右側：規格摘要
tf = _tb(s, 8.3, 1.85, 4.2, 0.35)
_para(tf, "SYSTEM SNAPSHOT", size=10, color=C_ACCENT, font=MONO, first=True, space_after=0)
specs = [
    ("三類動作原語", "LAUNCH / INTERACT / INPUT"),
    ("元件能力家族", "56 families × 20 capabilities"),
    ("資料庫", "19 個資料表 · 逾 23 萬列"),
    ("模型", "MiniLM INT8 · YOLOv8 · PP-OCRv4"),
    ("單元測試", "257 項 · 0 失敗"),
    ("評測集", "100 題 · EASY/MEDIUM/HARD/EXTREME"),
]
yy = 2.35
for k, v in specs:
    tf = _tb(s, 8.3, yy, 4.2, 0.24)
    _para(tf, k, size=9.5, color=RGBColor(0x94, 0xA9, 0xBC), first=True, space_after=0)
    tf = _tb(s, 8.3, yy + 0.23, 4.2, 0.3)
    _para(tf, v, size=12, color=C_WHITE, bold=True, first=True, space_after=0)
    yy += 0.72
_page[0] += 1

# ══════════════════════════════════════════════════════════════════════
# 02 導覽
# ══════════════════════════════════════════════════════════════════════
s = content_slide("這份文件怎麼讀", "全書 45 頁，六個部分；每頁都有『原理 / 公式 / 實作』其中至少一種")
rows = [
    ["部分", "主題", "你會看到什麼"],
    ["I", "定位與哲學", "為什麼這樣設計、法律邊界、六條不可妥協的原則"],
    ["II", "系統架構", "L0–L5 分層、一次任務的完整時序、延遲預算"],
    ["III", "感知層", "無障礙樹、六維閘門、元件能力圖譜、VoI、視覺與 OCR"],
    ["IV", "理解與決策", "語意向量、混合打分公式、CUA-S1、歧義、安全自動機"],
    ["V", "動作與世界模型", "3 原語、動作契約、滾動與設值幾何、Twin、MCTS、恢復"],
    ["VI", "學習・資料・評測", "姿態模型、遙測、資料庫、評測集、實測與除錯實錄"],
]
table(s, rows, y=1.52, col_widths=[0.6, 2.1, 7.4], size=11.5, row_h=0.42)
tf = _tb(s, 0.62, 5.35, 12.1, 1.1)
_para(tf, "圖例：", size=11, color=C_MUTED, first=True, space_after=3)
_para(tf, "◆ 綠色框 = 已實作並通過測試    ◇ 灰色框 = 設計完成但尚未接線    "
          "公式區以等寬字體呈現，可直接對照程式碼。",
      size=11.5, color=C_BODY, space_after=0)

# ══════════════════════════════════════════════════════════════════════
# 03 PART I
# ══════════════════════════════════════════════════════════════════════
section_slide("I", "定位與哲學", "POSITIONING & PHILOSOPHY",
              ["WAE 是什麼、不是什麼", "六條不可妥協的設計原則", "綠色通道：法律與技術邊界"])

# 04 是什麼/不是什麼
s = content_slide("WAE 是什麼，不是什麼", "先把邊界說清楚，才能談架構")
tf = _tb(s, 0.62, 1.5, 5.9, 0.35)
_para(tf, "它 是", size=15, color=RGBColor(0x0F, 0x76, 0x6E), bold=True, first=True, space_after=0)
bullets(s, [
    "一個跑在手機上的通用 GUI Agent：聽一句中文，自己看畫面、自己決定、自己動手",
    "只用 AccessibilityService（無障礙服務）這個官方公開介面去『看』與『點』",
    "所有理解都在本機完成：文字向量、圖示檢測、OCR 三套 ONNX 模型離線推論",
    "所有知識來自可審核的 TSV 資料庫，程式碼裡不寫任何 App 名稱",
], x=0.62, y=1.9, w=5.85, h=4.2, size=12.5, gap=7)
panel(s, 6.62, 1.44, 6.1, 4.55)
tf = _tb(s, 6.82, 1.56, 5.7, 0.35)
_para(tf, "它 不 是", size=15, color=RGBColor(0xB4, 0x53, 0x09), bold=True, first=True, space_after=0)
bullets(s, [
    "不是自動化腳本：沒有寫死的座標、沒有寫死的頁面流程",
    "不是雲端 Agent：沒有任何網路權限，不上傳畫面、不上傳文字",
    "不是破解工具：不解析 APK / AXML / DEX，不讀別的 App 的資源",
    "不是『什麼都敢點』：不確定就停，並明確告訴你為什麼做不到",
], x=6.82, y=1.98, w=5.7, h=3.9, size=12.5, gap=7)
takeaway(s, "核心判準：能不能在不碰灰色地帶的前提下，做到『看懂、做對、說清楚』。")

# 05 六條哲學
s = content_slide("六條設計哲學", "這六條是後面所有技術決策的依據")
ph = [
    ("01", "誠實失敗", "做不到就明確說『無法完成』並停止，絕不用亂點製造假進度。"),
    ("02", "資料驅動", "App 清單、元件能力、詞表全部放 TSV；程式碼只留演算法。"),
    ("03", "綠色通道", "只用公開 SDK 與 AccessibilityNodeInfo 的公開欄位，CI 強制檢查。"),
    ("04", "可審計", "每個決策都印出理由（分數、門檻、命中詞），事後能追。"),
    ("05", "低延遲", "感知模組按『資訊增益』調度，資訊夠就不跑，平均 < 600ms/步。"),
    ("06", "跨 App 泛化", "以『元件能力 + 語意用途』取代『某 App 的某個按鈕』。"),
]
x0, y0 = 0.62, 1.5
for i, (num, name, desc) in enumerate(ph):
    col, row = i % 3, i // 3
    x = x0 + col * 4.06
    y = y0 + row * 2.42
    panel(s, x, y, 3.86, 2.18, fill=C_WHITE, border=RGBColor(0xE2, 0xE8, 0xF0))
    _rect(s, x, y, 3.86, 0.07, C_ACCENT)
    tf = _tb(s, x + 0.22, y + 0.22, 0.6, 0.3)
    _para(tf, num, size=15, color=RGBColor(0xB6, 0xC4, 0xD4), bold=True, font=MONO, first=True, space_after=0)
    tf = _tb(s, x + 0.85, y + 0.2, 2.8, 0.35)
    _para(tf, name, size=15, color=C_INK, bold=True, first=True, space_after=0)
    tf = _tb(s, x + 0.22, y + 0.75, 3.42, 1.3)
    _para(tf, desc, size=11.5, color=C_BODY, first=True, space_after=0, line_spacing=1.25)

# 06 綠色通道
s = content_slide("綠色通道：法律與技術邊界", "為什麼用無障礙服務而不是自動化框架")
rows = [
    ["手段", "技術可行性", "合規風險", "本專案採用"],
    ["AccessibilityService", "高：可取得完整節點樹與動作", "低：Android 公開 API、使用者手動授權", "✔ 唯一通道"],
    ["MediaProjection 截圖", "中：需使用者同意、有隱私提示", "中：畫面含個資", "✔ 僅在無障礙樹不足時，且不落盤"],
    ["APK / AXML 靜態解析", "高", "高：繞過簽章與資源保護", "✘ 一律不做"],
    ["讀取他人 App 私有資料", "低", "極高", "✘ 一律不做"],
    ["Root / 注入 / Hook", "高", "極高，且破壞系統完整性", "✘ 一律不做"],
]
table(s, rows, y=1.52, col_widths=[2.6, 3.2, 3.4, 1.6], size=11, row_h=0.5)
bullets(s, [
    ("H", "CI 是怎麼擋的（不是靠自覺）"),
    ("1", "NoAppSpecificCodeTest：掃描所有生產程式碼，與 App 名稱資料庫反向比對，命中即失敗"),
    ("1", "GreenZoneGuardTest：禁止出現 getResourcesForApplication、ApkFile、ZipFile、dalvik 等符號"),
    ("1", "DataSourcePolicyTest：要求所有清單類資料都必須存在於 assets 資料庫且非空"),
], x=0.62, y=4.75, w=12.1, h=1.4, size=11.5, gap=4)

# ══════════════════════════════════════════════════════════════════════
# 07 PART II
# ══════════════════════════════════════════════════════════════════════
section_slide("II", "系統架構", "SYSTEM ARCHITECTURE",
              ["L0–L5 分層與職責", "一次任務的完整時序", "延遲預算與熱路徑"])

# 08 L0-L5 圖
s = content_slide("L0–L5 分層總覽", "每一層只做一件事；跨層溝通靠不可變資料結構")
layer_diagram(s, [
    ("L0", "直達層 Deep Link", "已匯入的 App 能力規則 → 直接開啟目標頁，省下 5–8 步"),
    ("L1", "感知層 Perception", "無障礙樹採集 + 鄰近文字焊接 + 六維閘門 + VoI 調度視覺/OCR"),
    ("L1.5", "語意層 Semantics", "詞林同義詞 + MiniLM 384-D 向量 + 混合打分"),
    ("L3", "決策層 Decision", "CUA-S1 softmax、三條件門檻、歧義分流、能力約束"),
    ("L4", "執行層 Action", "TOCTOU 防護、動作契約、手勢/輸入降級鏈"),
    ("L5", "歸因層 Attribution", "頁面簽名 Jaccard、三態因果判定、恢復與熔斷"),
])
panel(s, 8.25, 1.45, 4.25, 4.55, fill=C_WHITE)
tf = _tb(s, 8.45, 1.6, 3.9, 0.35)
_para(tf, "跨層的三個不變量", size=13, color=C_INK, bold=True, first=True, space_after=6)
bullets(s, [
    "不可變資料：CandidateOption / OptionSet 一旦建立就不再修改（copy 產生新版本）",
    "幀一致性：決策與執行必須在同一 frameId + windowId，否則丟棄重來",
    "單一真相：所有模組讀同一棵樹、同一份資料庫，不出現兩套標準",
], x=8.45, y=2.05, w=3.9, h=3.6, size=11.5, gap=8)
tf = _tb(s, 8.45, 5.0, 3.9, 0.9)
_para(tf, "熱路徑只走 L0/L1/L3，其餘層可被跳過", size=10.5, color=C_MUTED, first=True, space_after=0)

# 09 時序 + 延遲
s = content_slide("一次任務的完整時序", "從一句中文到最後一個手勢，共 11 個階段")
steps = [
    ("1", "指令拆解", "詞庫連接詞切分 → 動作詞匹配 → 產生 LocalGoal 序列"),
    ("2", "App 解析", "App 元資料庫比對別名 → 取得 packageName 作為目標提示"),
    ("3", "啟動/直達", "L0 Deep Link 或 啟動 App；LAUNCH 目標在此完成"),
    ("4", "感知", "採集節點 → 焊接語境 → 六維閘門 → 候選集"),
    ("5", "語意打分", "詞林 + 向量 + 角色一致性 → 混合分數"),
    ("6", "決策", "softmax + 三條件 → CLEAR_WIN / AMBIGUOUS / REJECT"),
    ("7", "動作", "契約檢查（冪等）→ 節點 refresh → 派發"),
    ("8", "等待", "事件驅動靜默 + 該控件歷史 P95 上限"),
    ("9", "歸因", "前後頁面簽名 Jaccard → APPLIED / NO_EFFECT / WRONG_EFFECT"),
    ("10", "恢復", "需要時進 L2A 去彈窗、L2B 滾動尋路、L3 微教學"),
    ("11", "收斂", "成功寫入 Twin 與遙測；失敗則依部分完成策略前進或停止"),
]
left = steps[:6]
right = steps[6:]
for col, group in enumerate((left, right)):
    x = 0.62 + col * 6.2
    y = 1.5
    for num, name, desc in group:
        _rect(s, x, y + 0.06, 0.34, 0.34, C_TITLE_BG)
        tf = _tb(s, x, y + 0.11, 0.34, 0.26)
        _para(tf, num, size=10, color=C_WHITE, font=MONO, first=True,
              space_after=0, align=PP_ALIGN.CENTER)
        tf = _tb(s, x + 0.46, y, 5.5, 0.26)
        _para(tf, name, size=12, color=C_INK, bold=True, first=True, space_after=0)
        tf = _tb(s, x + 0.46, y + 0.27, 5.5, 0.34)
        _para(tf, desc, size=10, color=C_MUTED, first=True, space_after=0)
        y += 0.8
panel(s, 0.62, 6.12, 12.1, 0.62, fill=RGBColor(0xEC, 0xFD, 0xF5), border=RGBColor(0x99, 0xF6, 0xE4))
tf = _tb(s, 0.86, 6.24, 11.7, 0.4)
_para(tf, "延遲預算：感知 ≤ 220ms ｜ 打分 ≤ 90ms ｜ 決策 ≤ 30ms ｜ 動作+等待 ≤ 900ms ｜ 全程目標 < 1.2s/步",
      size=11.5, color=RGBColor(0x0F, 0x51, 0x43), bold=True, first=True, space_after=0)

# ══════════════════════════════════════════════════════════════════════
# 10 PART III
# ══════════════════════════════════════════════════════════════════════
section_slide("III", "感知層", "PERCEPTION",
              ["無障礙樹採集與語境焊接", "六維可操作閘門與元件能力圖譜", "VoI 調度、圖示檢測與 OCR"])

# 11 L1 採集
s = content_slide("L1：無障礙樹採集與語境焊接", "把一棵扁平的節點樹，變成『帶語境的候選項』")
bullets(s, [
    ("H", "採集規則（WaeVisionAccessibilityService.collectNodesWithContextRecursive）"),
    ("1", "深度優先、上限 480 個節點、深度 42：防止異常 App 造成卡死"),
    ("1", "節點必須 isVisibleToUser 且有非零邊界；被鍵盤（IME）遮住的直接丟棄"),
    ("1", "收集子節點文字摘要（最多 6 個）作為父節點的『焊接語境』"),
    ("H", "語境焊接：解決『圖標按鈕沒有文字』"),
    ("1", "父節點本身沒文字時，用子節點摘要 + 位置區域 + 角色，組成描述字串"),
    ("CODE", '[ZONE|PARENT_ROLE|ROLE] 文字  →  例：[CENTER_LIST|LIST_ITEM|search] 搜尋'),
    ("1", "這個字串同時餵給詞林比對與向量模型，讓沒有文字的按鈕也『說得出自己是誰』"),
    ("H", "容器角色推導（供後續排除誤點）"),
    ("1", "RecyclerView/ListView → LIST_ITEM ｜ Toolbar → TOOLBAR ｜ BottomNavigation/TabLayout → BOTTOM_NAV"),
], x=0.62, y=1.46, w=12.1, h=5.3, size=12.5, gap=5)
takeaway(s, "巧思：不新增資料結構，而是把『鄰居的資訊』焊進候選描述，讓後續所有模組免費受益。",
         y=6.28)

# 12 六維閘門
s = content_slide("六維可操作閘門（Actionability Gate）", "在花任何算力之前，先把不可能操作的節點濾掉")
formula_panel(s, [
    "可操作  ⇔  visible ∧ enabled ∧ size ≥ 8dp",
    "        ∧ viewportCoverage ≥ 0.50",
    "        ∧ ¬behindModal ∧ ¬blacklisted",
    "",
    "viewportCoverage = |B ∩ V| / |A|",
    "  B = 節點邊界，V = 可視區，A = 節點面積",
], x=0.62, y=1.5, w=6.35, h=2.55, size=12)
bullets(s, [
    ("H", "六個維度各自擋掉什麼"),
    ("1", "可見性：isVisibleToUser=false（背景頁、被覆蓋）"),
    ("1", "啟用狀態：isEnabled=false（灰掉的按鈕）"),
    ("1", "尺寸：小於 8dp 的雜訊節點（分隔線、裝飾點）"),
    ("1", "覆蓋率：被螢幕邊緣裁掉超過一半的元素"),
    ("1", "遮擋：不在 topmost window 的節點（dialog 後面的頁面）"),
    ("1", "黑名單：連續點擊無效的節點（自癒機制，見第 36 頁）"),
], x=7.15, y=1.5, w=5.55, h=4.6, size=11.5, gap=6)
tf = _tb(s, 0.62, 4.35, 6.35, 1.6)
_para(tf, "為什麼先用幾何、不用模型？", size=12.5, color=C_INK, bold=True, first=True, space_after=4)
_para(tf, "幾何判斷是 O(1) 且零誤差；模型有延遲與幻覺。能被幾何否定的候選，"
          "就不該浪費 16ms 的 embedding。實測：一個繁忙頁面 100+ 節點，"
          "經此閘門後平均只剩 21 個進入打分。",
      size=11, color=C_BODY, space_after=0, line_spacing=1.3)

# 13 元件能力圖譜
s = content_slide("元件能力圖譜（Widget Capability Ontology）", "本輪新增：把『元件是什麼』升級為『元件能做什麼』")
bullets(s, [
    ("H", "問題：只知道名字，不知道能力"),
    ("1", "舊版角色推斷只看文字/圖示/viewId，結果看到滑桿只會點一下、看到進度條也照點"),
    ("H", "解法：兩軸分離，相乘才得到動作"),
    ("CODE", "A 軸 能力 ← 元件類別（先驗） + 運行期語意訊號（實證，優先）"),
    ("CODE", "B 軸 用途 ← 文字 / contentDescription / 圖示語意 / 頁面語境"),
    ("CODE", "動作 = 能力 ∩ 用途  →  SET_VALUE / TOGGLE / PICK_ONE / TAP …"),
    ("H", "資料：56 個元件家族 × 20 種能力，全部在 TSV"),
    ("1", "seek_bar → SET_VALUE, STEP_VALUE, DRAG, SCROLL_H, TAP ｜ switch → TOGGLE ｜ progress_bar → READ_ONLY"),
    ("1", "prior 欄位標記『類別先驗可不可信』：generic_view / compose_view 標為 weak，能力完全由運行期決定"),
    ("1", "文件：ui_widget_capability_ontology.tsv（由 tools/gen_widget_ontology.py 產生，可重現）"),
], x=0.62, y=1.46, w=12.1, h=5.3, size=12, gap=5)
takeaway(s, "關鍵洞察（使用者提出）：滑桿不等於調數值，同一個元件在不同 App 有不同用途；反之很多『調數值』其實是點擊。", y=6.32)

# 14 能力融合
s = content_slide("能力融合：先驗 vs 實證", "運行期訊號永遠壓過類別先驗——這是自繪元件也能正確歸類的關鍵")
rows = [
    ["運行期訊號（公開欄位）", "提升的能力", "處理原則"],
    ["getRangeInfo() 有值且 span>0", "SET_VALUE / DRAG", "互動型 → 移除 READ_ONLY；唯讀型（進度條）保留"],
    ["getActionList() 含 ACTION_SET_PROGRESS", "SET_VALUE（強證據）", "信心值提升至 0.95"],
    ["isCheckable / isChecked", "TOGGLE", "開關與勾選一律走切換語意"],
    ["getInputType() 為 TEXT 或 isEditable", "TEXT_INPUT", "移除 READ_ONLY"],
    ["getCollectionInfo() rows/columns", "SCROLL_V / SCROLL_H", "單列多欄 → 水平；其餘 → 垂直"],
    ["ACTION_EXPAND / ACTION_SELECT", "EXPAND / PICK_ONE", "為未來的選單與手風琴鋪路"],
]
table(s, rows, y=1.5, col_widths=[3.7, 2.6, 5.4], size=10.5, row_h=0.46)
code_block(s, [
    "val caps = if (family != null && family.strongPrior) family.capabilities else emptySet()",
    "if (signals.hasRange)     { caps += SET_VALUE; caps += DRAG; if (interactive) caps -= READ_ONLY }",
    "if (signals.setProgress)  { caps += SET_VALUE; caps -= READ_ONLY }",
    "if (signals.isCheckable)  { caps += TOGGLE }",
    "if (signals.isClickable)  { caps += TAP; caps -= READ_ONLY }",
    "adjustable = SET_VALUE in caps && READ_ONLY !in caps",
], y=4.95, h=1.35, size=10)
tf = _tb(s, 0.62, 6.42, 12.1, 0.4)
_para(tf, "巧思：先驗只是「起點」，不是「結論」。所有能力都必須通過運行期證據的檢驗，"
          "因此 Flutter / Compose / 自繪 SeekBar 也能被正確識別。",
      size=11, color=C_MUTED, first=True, space_after=0)

# 15 用途→分類
s = content_slide("用途 → 分類：把人的常識寫進資料", "沒有這一層，「亮度」會被語意模型判給「音效與震動」")
bullets(s, [
    ("H", "實際踩到的失敗"),
    ("CODE", "「把亮度調高」→ 音效與震動 0.59 ／ 已連接的裝置 0.58 ／ 顯示螢幕 0.58"),
    ("1", "語意相似度在這種『屬性 → 分類』的映射上完全不可靠；模型認為都差不多"),
    ("H", "解法：新增一張 14 列的關聯表"),
    ("CODE", "display → 顯示 → 亮度｜字體大小｜顯示大小｜逾時｜深色模式｜更新率…"),
    ("CODE", "sound   → 音效與震動 → 音量｜震動強度｜鈴聲｜通知音…"),
    ("1", "決策時：先查目標屬性屬於哪個分類，只在那個分類的入口列中挑選"),
    ("1", "結果：顯示螢幕 0.72、margin 0.72（原本 0.58 且與對手同分）"),
    ("H", "防呆：兩個候選差距 < 0.05 就不鑽"),
    ("CODE", "if (second != null && margin < 0.05) → REJECT(AMBIGUOUS_NAVIGATION)"),
], x=0.62, y=1.46, w=12.1, h=5.4, size=12, gap=5)

# 16 VoI
s = content_slide("VoI：把感知模組當成『有成本的服務』", "不是每頁都跑模型；只在值得的時候花算力")
formula_panel(s, [
    "max_{S ⊆ M}  Σ_{m∈S} ΔEIG(m)",
    "s.t.          Σ_{m∈S} cost(m) ≤ B",
    "",
    "ΔEIG(m) ≈ 1 − max(p_top1, p_top2)",
    "  p 由上一輪 softmax 取得；差距越小，資訊越值錢",
], x=0.62, y=1.5, w=6.3, h=2.6, size=12)
bullets(s, [
    ("H", "三個實測決策"),
    ("1", "候選 ≥ 3 且文字齊全 → 兩個模型都不跑（省 1.0s+）"),
    ("1", "候選稀疏／無文字 → 兩個都跑（此時模型是唯一資訊來源）"),
    ("1", "只有決策 REJECT 或 AMBIGUOUS，且尚未升級過 → 強制補跑一次 OCR"),
    ("H", "成本上限"),
    ("1", "圖示檢測 180ms、OCR 900ms，預算 1200ms（強制模式 2000ms）"),
    ("1", "每個任務只允許升級一次，避免『每輪都重跑 OCR』的成本災難"),
], x=7.1, y=1.5, w=5.6, h=4.7, size=11.5, gap=6)
takeaway(s, "巧思：把「要不要看」也當成決策問題，而不是固定流程——這是延遲從 1.2s 降到 0.4s 的主因。",
         y=6.3)

# 17 圖示檢測
s = content_slide("視覺通道 ①：圖示檢測（OmniParser icon_detect）", "單類別 YOLOv8，FP16，輸出所有「像按鈕」的框")
bullets(s, [
    ("H", "模型與前處理"),
    ("1", "輸入 640×640 letterbox，保持長寬比、灰邊填充，避免形變"),
    ("1", "FP16 權重、ORT Android 1.22，CPU 執行緒數自適應（A55 實測 180ms/幀）"),
    ("H", "後處理：置信度 → NMS → 焊接"),
    ("CODE", "IoU = |A∩B| / |A∪B| ；  保留 IoU < 0.45 的最高分框"),
    ("1", "最小面積門檻（螢幕面積 0.15%%）過濾掉文字碎片"),
    ("1", "框與無障礙節點做「焊接」：IoU > 0.5 視為同一元件，繼承其文字與角色"),
    ("H", "為什麼還需要它"),
    ("1", "WebView / 遊戲 / 自繪 UI 的無障礙樹常常是空的，此時視覺是唯一線索"),
], x=0.62, y=1.46, w=12.1, h=5.3, size=12, gap=5)
code_block(s, [
    "boxes = yolo(image)                    # [x1,y1,x2,y2,conf] × N",
    "keep  = nms(boxes, iou_thr = 0.45)     # 類別單一，直接做 greedy NMS",
    "cands = keep.filter { area(it) / screenArea >= 0.0015 }",
    "weld(cands, a11yNodes)                 # IoU ≥ 0.5 → 合併為 HYBRID_WELDED",
], y=5.15, h=1.1, size=10)

# 18 OCR
s = content_slide("視覺通道 ②：PP-OCRv4 與後處理", "把畫面上的文字變成可被語意模型理解的候選")
bullets(s, [
    ("H", "兩階段：偵測 + 辨識"),
    ("1", "偵測（ch_PP-OCRv4_det）：輸出機率圖 → 連通域 → 最小外接旋轉框"),
    ("1", "辨識（ch_PP-OCRv4_rec）：固定高度 48、寬度按比例縮放，CTC 解碼"),
    ("CODE", "text = argmax_t  softmax(W · h_t)     # CTC per-frame 解碼後合併重複字元"),
    ("H", "後處理（容易被忽略、但決定成敗）"),
    ("1", "中文標點正規化、全半形統一、去除單字雜訊（< 2 字且置信度 < 0.6）"),
    ("1", "繁簡不做強制轉換，交給 OpenCC 詞庫在比對時做等價處理"),
    ("1", "同一行碎片合併：垂直重疊 > 60% 且水平間距 < 0.6 倍字高 → 併為一句"),
    ("H", "與無障礙樹的關係"),
    ("1", "OCR 產生的候選 roleTag 記為 ocr.text，且沒有 live node（只能點、不能讀寫屬性）"),
], x=0.62, y=1.46, w=12.1, h=5.2, size=12, gap=5)
tf = _tb(s, 0.62, 6.35, 12.1, 0.5)
_para(tf, "這一限制正是「數值調整」必須拒絕無 live node 候選的原因：讀不到 RangeInfo 就不可能設定數值。",
      size=11, color=RGBColor(0xB4, 0x53, 0x09), first=True, space_after=0)

# ══════════════════════════════════════════════════════════════════════
# 19 PART IV
# ══════════════════════════════════════════════════════════════════════
section_slide("IV", "理解與決策", "SEMANTICS & DECISION",
              ["MiniLM 向量與混合打分", "CUA-S1 softmax 與三條件門檻", "歧義處理、數值決策、安全自動機"])

# 20 MiniLM
s = content_slide("語意通道：MiniLM-L6-v2 INT8 384-D", "把「一句話」與「一個元件」放進同一個向量空間")
formula_panel(s, [
    "sim(a, b) = (a · b) / (‖a‖₂ ‖b‖₂)",
    "",
    "hybrid = max( s_db , s_embed , rule )",
    "",
    "s_embed ∈ [0, 1]，負值截斷為 0",
], x=0.62, y=1.5, w=5.6, h=2.3, size=12)
bullets(s, [
    ("H", "工程細節（決定能不能跑在手機上）"),
    ("1", "INT8 量化 + ORT 記憶體映射（mmap），冷啟動不複製 22MB 權重"),
    ("1", "輸入長度截斷 64 token；批次上限 24 條，避免單幀爆量"),
    ("1", "LRU 快取 512 條（以描述字串為鍵），同頁重複查詢命中率 > 60%"),
    ("1", "單條前向約 16ms（A55），24 條批次約 230ms"),
    ("H", "為什麼要快取"),
    ("1", "同一頁每輪候選幾乎不變；沒有快取時每步多花 200ms"),
], x=6.5, y=1.5, w=6.2, h=4.6, size=11.5, gap=6)
tf = _tb(s, 0.62, 4.05, 5.6, 2.2)
_para(tf, "詞林與同義詞的分工", size=12.5, color=C_INK, bold=True, first=True, space_after=4)
_para(tf, "詞林（HIT-Cilin 16,504 組、反義 38,276 組）負責『確定性同義』："
          "搜尋≈查找≈尋找。向量負責『模糊近似』：亮度≈螢幕亮度。"
          "兩者取 max，既不互相拖累，也避免向量幻覺主導。",
      size=11, color=C_BODY, space_after=0, line_spacing=1.3)

# 21 混合打分
s = content_slide("混合打分：五個訊號的加權合成", "每一項都必須能被解釋，否則事後無法除錯")
code_block(s, [
    "hybrid(goal, cand) = clamp( max(",
    "      s_db(goal, cand),        # 詞林同義 / 動作詞角色一致  → 0.84 ~ 0.86",
    "      s_embed(goal, cand),     # MiniLM cosine               → 0 ~ 1",
    "      rule(cand)               # 明確規則（如 id 含 search） → 0.7 ~ 0.9",
    "), 0, 1)",
    "",
    "if (actionKind == TYPE && !cand.actionable)        hybrid *= 0.75   # 打字目標必須可操作",
    "if (cand.isReadOnlyOnly && action in TAP/ADJUST)   hybrid *= 0.75   # 唯讀元件不該被點",
    "if (telemetry.demoted(pkg, selector))               hybrid -= 0.15   # 歷史成功率 < 60%",
], y=1.5, h=2.75, size=10.5)
rows = [
    ["訊號", "來源", "典型值", "解決什麼"],
    ["s_db", "HIT-Cilin + command_lexicon", "0.84–0.86", "同義詞與動作詞角色一致性"],
    ["s_embed", "MiniLM INT8 384-D", "0–1", "沒見過的說法、口語化指令"],
    ["rule", "viewId / 元件能力 / 容器型別", "0.70–0.90", "強證據（如 id=search_plate）"],
    ["penalty", "可操作性 + 遙測", "×0.75 / −0.15", "避免選到唯讀、或老是失敗的選擇器"],
]
table(s, rows, y=4.45, col_widths=[1.3, 3.6, 1.6, 5.3], size=10.5, row_h=0.4)

# 22 CUA-S1
s = content_slide("L3 決策：CUA-S1 與三條件門檻", "不是「取最高分」，而是「夠不夠格」")
formula_panel(s, [
    "z_i = w·φ(goal, c_i) + s_i + b_i",
    "p_i = exp(z_i/τ) / Σ_j exp(z_j/τ)",
    "",
    "CLEAR_WIN ⇔ p_1 ≥ θ_abs(n)",
    "        ∧ (p_1 − p_2) ≥ θ_margin(n)",
    "        ∧ sim_1 ≥ θ_sim",
], x=0.62, y=1.5, w=6.1, h=2.7, size=12)
bullets(s, [
    ("H", "三個門檻為什麼都要"),
    ("1", "θ_abs：絕對信心（候選多時 softmax 會被攤薄，需隨 n 調整）"),
    ("1", "θ_margin：與第二名的差距（避免 0.31 vs 0.30 的硬選）"),
    ("1", "θ_sim：語意下限（避免全部候選都很低時仍選出一個）"),
    ("H", "另外兩條硬規則"),
    ("1", "近同分防護：兩者 sim ≥ 0.80 且差距 < 0.03 且有兩個都可用 → 一定進澄清"),
    ("1", "高相似捷徑：sim ≥ 0.72 且 p1 > p_reject 且非底部導航 → 直接 CLEAR_WIN"),
], x=6.95, y=1.5, w=5.75, h=4.7, size=11.5, gap=6)
tf = _tb(s, 0.62, 4.45, 6.1, 1.8)
_para(tf, "τ（溫度）的作用", size=12.5, color=C_INK, bold=True, first=True, space_after=4)
_para(tf, "τ > 1 會讓分布更平（更保守），τ < 1 更尖銳。本專案在候選數 n 大時"
          "以 θ_abs(n) 補償，而不是改 τ——因為 τ 會同時影響所有情境，"
          "門檻分開調比較好除錯。",
      size=11, color=C_BODY, space_after=0, line_spacing=1.3)

# 23 歧義
s = content_slide("歧義處理：三種情況，三種策略", "寧可問一次，也不要錯一輩子")
rows = [
    ["情況", "判定條件", "處理"],
    ["明確歧義", "p1、p2 都高且 margin 小，且兩者都真的可操作",
     "彈出 Top-3 澄清，等使用者點選；不進滾動恢復"],
    ["近同分", "top1/top2 語意分 ≥ 0.80、差距 < 0.03、且不是同一標籤",
     "強制澄清（避免 0.321 vs 0.321 的硬選）"],
    ["策略偏好", "使用者說「隨便一個」「第一封」，targetSpecificity = CLASS",
     "在內容元素中依位置取第一個，不走語意競賽"],
    ["語意不足", "所有候選 sim 都很低（< 0.20）",
     "明確拒絕並說明：此頁找不到對應目標"],
]
table(s, rows, y=1.5, col_widths=[1.7, 5.4, 5.0], size=11, row_h=0.62)
bullets(s, [
    ("H", "CLASS 級目標的回退邏輯（實作細節）"),
    ("1", "排除：搜尋框、輸入框、底部導航、工具列——它們不是「內容」"),
    ("1", "優先：LIST_ITEM / CARD 容器；其次語意分；再次面積；最後取最上方者（保證可重現）"),
    ("1", "完全沒有內容元素 → EMPTY_CONTENT_SET，立刻停止，不再重試"),
    ("1", "有元素但語意全都不可信（< 0.20）→ NO_PLAUSIBLE_TARGET，明確拒絕"),
], x=0.62, y=4.6, w=12.1, h=1.9, size=11, gap=4)

# 24 數值調整決策
s = content_slide("數值調整的決策樹（本輪新增）", "一般語意競賽在此完全失效：要操作的是沒有文字的滑桿")
code_block(s, [
    "evaluateAdjustValue(goal, options):",
    "  adjustable = options.filter { it.canAdjustValue && it.enabled }",
    "  if (adjustable.isNotEmpty()):",
    "      label = options.filter { it.hasOwnSemanticEvidence }.maxBy { it.score }",
    "      match = ValueTargetResolver.associate(label, options)   # 幾何配對",
    "      return CLEAR_WIN(match?.target ?: adjustable.first(), reason = ADJUST_VALUE_TARGET)",
    "",
    "  category = PurposeCategoryMap.categoryFor(goal.payload)     # 亮度 → 顯示",
    "  pool     = options.filter { category.matches(it.text) } ?: options",
    "  drill    = pool.filter { it.isNavigable }.maxBy { it.score }",
    "  if (drill != null && margin(drill) >= 0.05) return CLEAR_WIN(drill, DRILLDOWN)",
    "  return REJECT(NO_ADJUSTABLE_COMPONENT)                      # 誠實失敗",
], y=1.5, h=3.05, size=10)
bullets(s, [
    ("H", "為什麼要「配對」而不是直接找滑桿"),
    ("1", "滑桿沒有文字 → 語意分永遠 0；標籤「亮度」有分但不可操作。兩者必須用幾何關聯起來。"),
    ("1", "關聯規則：包含 → 正下方（gap ≤ 200dp，水平重疊 ≥ 30%）→ 同列右側（±28dp）"),
    ("H", "三種「調數值」的形態都支援"),
    ("1", "連續滑桿 → ACTION_SET_PROGRESS 或拖曳；+/− 按鈕 → 連續點擊；下拉選項 → 點開再選"),
], x=0.62, y=4.75, w=12.1, h=1.7, size=11.5, gap=4)

# 25 安全自動機
s = content_slide("安全自動機：把「不能做」寫成狀態機", "不是靠提示詞，而是靠可驗證的規則")
rows = [
    ["風險類型", "觸發條件（節錄）", "判定"],
    ["付款 / 轉帳", "可見文字或目標含付款、金額、轉帳語彙", "CONFIRM（需人工確認）"],
    ["刪除 / 清除資料", "目標或頁面語意為刪除、清除、格式化", "CONFIRM"],
    ["帳號 / 驗證碼", "出現 OTP、驗證碼、密碼欄位", "ABORT（整段任務中止）"],
    ["離開目標 App", "動作後 package 改變且非同一可見介面", "記錄並重新確認"],
    ["內容列誤判", "目標位於 LIST_ITEM（信件主旨可能剛好含『驗證碼』字樣）", "豁免，不觸發"],
]
table(s, rows, y=1.5, col_widths=[2.3, 6.5, 3.3], size=10.5, row_h=0.52)
bullets(s, [
    ("H", "兩個容易被忽略的設計"),
    ("1", "詞邊界：英文關鍵詞必須是完整單詞（`pay` 不能命中 `play`）——曾因此誤攔播放鍵"),
    ("1", "內容列豁免：Gmail 收件匣的信件主旨常含「驗證碼」，那是內容不是風控關卡"),
    ("1", "所有判定都寫入 WAE_SAFETY 日誌，包含 gate、action、目標、package，可事後審計"),
], x=0.62, y=4.6, w=12.1, h=1.9, size=11.5, gap=5)

# ══════════════════════════════════════════════════════════════════════
# 26 PART V
# ══════════════════════════════════════════════════════════════════════
section_slide("V", "動作與世界模型", "ACTION & WORLD MODEL",
              ["3 類原語與細分碼", "動作契約、幾何公式、降級鏈", "Twin 重放、MCTS、恢復狀態機"])

# 27 動作空間
s = content_slide("動作空間：3 類原語 × 細分碼", "收斂不是為了簡化，而是為了「跨 App 有共同語言」")
rows = [
    ["原語", "細分碼", "意義", "Twin 儲存成本"],
    ["LAUNCH", "L:APP / L:LINK / L:NOTIF", "啟動 App、深鏈直達、開通知", "5–7 bytes"],
    ["INTERACT", "I:TAP / I:SDN / I:SUP / I:SLR / I:LNG", "點、上下滾、橫滑、長按", "5 bytes"],
    ["INTERACT", "I:BAK / I:WAI / I:SUB / I:VAL", "返回、等待、送出、設定數值", "5 bytes"],
    ["INPUT", "N:TXT(slot) / N:CLR / N:RPL", "輸入、清除、整段替換", "8–14 bytes"],
]
table(s, rows, y=1.5, col_widths=[1.6, 4.6, 4.0, 1.9], size=11, row_h=0.5)
bullets(s, [
    ("H", "為什麼是三類（完備性論證）"),
    ("1", "任何使用者操作，必然落在：開啟某個東西 / 與畫面互動 / 輸入文字——三者互斥且窮盡"),
    ("1", "細分碼只影響「怎麼做」，不影響「做什麼」，因此策略層只需理解三類"),
    ("H", "省 token 的實際效果"),
    ("1", "一步從平均 40 bytes（完整 JSON）降到 5–14 bytes；1000 步的軌跡從 40KB 降到 12KB"),
    ("1", "Twin 以「前置條件 + 動作」為鍵，動作部分可以直接用短碼比對"),
], x=0.62, y=4.35, w=12.1, h=1.9, size=11.5, gap=5)

# 28 動作契約
s = content_slide("動作契約：冪等、三態與事後歸因", "避免「同一個動作做兩次」，並能證明「真的生效了」")
formula_panel(s, [
    "intentKey = H( pkg ‖ action ‖ selector",
    "             ‖ posture ‖ pageSpine ‖ slot )",
    "",
    "if ledger.has(intentKey) within 2.5s → SKIP",
    "",
    "effect ∈ { APPLIED, NO_EFFECT, WRONG_EFFECT }",
], x=0.62, y=1.5, w=5.9, h=2.75, size=11.5)
bullets(s, [
    ("H", "三態怎麼判定"),
    ("1", "APPLIED：頁面簽名改變且目標子樹雜湊改變（或數值調整時，量到的值確實變了）"),
    ("1", "NO_EFFECT：簽名幾乎不變 → 換手勢補打一次，再不行就換候選"),
    ("1", "WRONG_EFFECT：package 或頁面型態跑到非預期的地方 → 立即回退並記錄"),
    ("H", "巧思：selectorKey 的設計"),
    ("1", "優先用 viewId（最穩定），其次角色+文字，最後才用座標"),
    ("1", "座標放最後，因為它最容易因版面微調而失效；但也比完全沒有好"),
], x=6.75, y=1.5, w=5.95, h=4.65, size=11.5, gap=6)
tf = _tb(s, 0.62, 4.5, 5.9, 1.6)
_para(tf, "為什麼要有 2.5 秒窗口", size=12.5, color=C_INK, bold=True, first=True, space_after=4)
_para(tf, "同一個 intent 在短時間內重複出現，通常代表「上一輪沒生效」而不是「使用者想再做一次」。"
          "窗口太短會漏掉真正需要重複的動作（例如連按 +），太長會卡住合理重試。",
      size=11, color=C_BODY, space_after=0, line_spacing=1.3)

# 29 精準滾動
s = content_slide("精準滾動：先問容器，再算距離", "盲滑是效率殺手，過衝比滑不到更糟")
formula_panel(s, [
    "d = clamp( |y_target − y_center| + margin ,",
    "            0.125·H , 0.50·H )",
    "",
    "單次上限 0.80·H，見底判定需連續兩次",
    "頁面簽名不變 且 目標仍缺失",
], x=0.62, y=1.5, w=6.1, h=2.6, size=11.5)
bullets(s, [
    ("H", "三層策略"),
    ("1", "① 無障礙滾動：找到可滾動容器下 ACTION_SCROLL_FORWARD/BACKWARD（最快、可預測）"),
    ("1", "② 手勢兜底：容器不支援時才發手勢，距離由上式計算"),
    ("1", "③ 掃描模式：目標完全不在樹中，最多 5 次；每次都檢查「是否見底」"),
    ("H", "貼邊保護"),
    ("1", "目標距邊界 < 48px 視為「未完全可見」，先滾再點，避免點擊偏移"),
], x=6.9, y=1.5, w=5.8, h=4.6, size=11.5, gap=6)
tf = _tb(s, 0.62, 4.35, 6.1, 1.8)
_para(tf, "為什麼是 1/8 到 1/2 屏", size=12.5, color=C_INK, bold=True, first=True, space_after=4)
_para(tf, "小於 1/8 屏：步數爆炸（長列表要滑十幾次）。大於 1/2 屏：容易把目標滑過頭，"
          "而且手指長距離滑動的軌跡更容易被系統判定成 fling，造成不可控的慣性。",
      size=11, color=C_BODY, space_after=0, line_spacing=1.3)

# 30 數值設定幾何
s = content_slide("數值設定：SET_PROGRESS 優先，拖曳兜底", "四層，每層都用「讀回的值」驗證，不看畫面變化")
formula_panel(s, [
    "v* = clamp( v_min + f·(v_max − v_min), v_min, v_max )",
    "",
    "x(v) = L + inset + ((v − v_min)/(v_max − v_min))·(W − 2·inset)",
    "",
    "達標 ⇔ |v_obs − v*| ≤ max(1, 0.04·(v_max − v_min))",
], x=0.62, y=1.5, w=6.2, h=2.7, size=11.5)
bullets(s, [
    ("H", "派發階梯"),
    ("1", "① ACTION_SET_PROGRESS（API 24+，直接把值設成目標，誤差 0）"),
    ("1", "② 拖曳：由「目前值」算出拇指位置，拖到目標位置（沒有 SET_PROGRESS 的 App）"),
    ("1", "③ 一次修正：依讀回的實際值再補拖（拖曳有物理誤差）"),
    ("1", "④ 全部失敗 → 明確回報未生效，不亂點"),
    ("H", "三個安全檢查（都來自實戰）"),
    ("1", "量程 span ≤ 0 → 拒絕（載入中的進度條曾讓任務誤報成功）"),
    ("1", "候選是唯讀元件 → 拒絕（純顯示的進度條不可操作）"),
    ("1", "沒有 live node（OCR 候選）→ 只能點，不算完成"),
], x=7.0, y=1.5, w=5.7, h=4.7, size=11, gap=5)

# 31 輸入注入
s = content_slide("輸入注入：四段降級鏈", "打字失敗是最常見的失敗；必須有可量測的退路")
rows = [
    ["順序", "策略", "適用情況", "失敗訊號"],
    ["①", "ACTION_SET_TEXT", "標準 EditText", "返回 false"],
    ["②", "FOCUS → SET_TEXT", "尚未取得焦點的輸入框", "文字長度仍為 0"],
    ["③", "剪貼簿貼上", "某些 App 禁用 SET_TEXT", "剪貼簿無效或被攔"],
    ["④", "逐字注入（CHAR）", "自繪輸入框、WebView", "字元掉落"],
]
table(s, rows, y=1.5, col_widths=[1.0, 3.4, 4.2, 3.5], size=11, row_h=0.5)
bullets(s, [
    ("H", "送出（SUBMIT）也是三段"),
    ("1", "ACTION_IME_ENTER（API 30+）→ 點鍵盤送出鍵 → 點第一個搜尋建議"),
    ("1", "每次都重新偵測「輸入框是否仍聚焦」來決定要不要再往下試"),
    ("H", "巧思：為什麼不是一次全部做完"),
    ("1", "每段都有副作用（貼上會覆蓋剪貼簿、逐字注入會觸發聯想），只在前一段失敗時才付出代價"),
], x=0.62, y=4.5, w=12.1, h=1.9, size=11.5, gap=5)

# 32 時序模型
s = content_slide("時序模型：P95 自適應等待", "固定 sleep 是延遲與不穩定的雙重來源")
formula_panel(s, [
    "wait(selector) = clamp( P95(響應延遲) × 1.15,",
    "                       300ms, 2000ms )",
    "",
    "響應延遲 = 派發時刻 → 第一次內容變化事件",
    "（不是整個動作耗時）",
], x=0.62, y=1.5, w=6.1, h=2.6, size=11.5)
bullets(s, [
    ("H", "量測的是「真正的響應」"),
    ("1", "很多動作的總耗時包含動畫與網路，但畫面在 200ms 就已經變了"),
    ("1", "以「第一次內容變化事件」為終點，才不會把網路等待算進 UI 等待"),
    ("H", "事件驅動靜默 ≥ 220ms"),
    ("1", "沒有事件才往下走；有事件就重置計時器，避免在動畫中操作"),
    ("H", "分組鍵"),
    ("1", "pkg + selector + action：同一個按鈕的返回鍵通常比進頁面快"),
], x=6.9, y=1.5, w=5.8, h=4.6, size=11.5, gap=6)

# 33 Twin
s = content_slide("Twin 世界模型：用「前置條件 + 動作」重放", "不是錄座標，而是錄「在什麼狀態下做了什麼」")
formula_panel(s, [
    "J(A, B) = |A ∩ B| / |A ∪ B|",
    "",
    "命中 ⇔ J(sigs_before, sigs_now) ≥ 0.40",
    "      ∧ 動作相同 ∧ 目標相似度 ≥ 0.60",
    "",
    "重放後仍以 postcondition 驗證（不信任歷史）",
], x=0.62, y=1.5, w=6.0, h=2.75, size=11.5)
bullets(s, [
    ("H", "為什麼用 before 而不是 after"),
    ("1", "實測：before 簽名 Jaccard = 1.0，after 只有 0.18——因為動作後的頁面變化太大"),
    ("1", "因此「能不能重放」取決於「現在是不是當初的狀態」，而不是「結果長得像不像」"),
    ("H", "TwinStep 記錄的欄位"),
    ("1", "pkg、spineBefore/After、action、target、posture、ok、ms、sigs、primitive、viewId、className、roleTag、grid"),
    ("1", "只記 256B 主幹簽名集合（top-k 節點雜湊），不是整棵樹"),
], x=6.85, y=1.5, w=5.85, h=4.65, size=11, gap=5)

# 34 MCTS
s = content_slide("MCTS 規劃：什麼都找不到時，先想一想", "在 Twin 圖上搜尋，再回到真機執行第一步")
formula_panel(s, [
    "UCT(s, a) = Q(s, a) + c·√( ln N(s) / N(s, a) )",
    "",
    "Q：該動作之後的加權成功率",
    "c：探索常數（本專案 1.41 ≈ √2）",
    "rollouts：120 次（行動裝置上的延遲上限）",
], x=0.62, y=1.5, w=6.1, h=2.6, size=11.5)
bullets(s, [
    ("H", "搜尋空間是「歷史圖」，不是無限想像"),
    ("1", "節點 = 頁面簽名；邊 = 曾經成功過的 TwinStep"),
    ("1", "因此不會規劃出一個從未存在過的動作序列"),
    ("H", "三道保險（都是實戰補上的）"),
    ("1", "預期成功率 < 0.25 → 不執行"),
    ("1", "定位到的候選必須有自身語意證據 → 否則不執行"),
    ("1", "候選必須有 viewId（可穩定定位）→ 否則不執行"),
], x=6.95, y=1.5, w=5.75, h=4.65, size=11, gap=5)

# 35 快路徑
s = content_slide("快路徑、序列重放與被動記錄", "讓重複的任務從 8 步降到 1 步")
bullets(s, [
    ("H", "① Twin 快路徑（單步）"),
    ("1", "命中條件：同 App、同動作、曾成功、前置條件 Jaccard ≥ 0.40"),
    ("1", "命中後直接派發，跳過 MiniLM 前向（省 60–500ms）"),
    ("H", "② 序列重放（整條鏈）"),
    ("1", "取最長成功鏈（≥ 3 步）→ 逐步驗證前置條件 → 執行"),
    ("1", "任何一步的前置條件不符 → 立即放棄整條鏈，退回正常管線"),
    ("H", "③ 被動記錄策略"),
    ("1", "使用者自己操作時，只記錄「結構 + 動作 + 目標」，不記錄輸入內容"),
    ("1", "高動態頁面（dynamicScore ≥ 0.75）不記錄，避免污染"),
    ("H", "④ 慣性防護（踩過的坑）"),
    ("1", "重放時若歷史的輸入步驟與當前任務的參數衝突（例如都是输入文字但內容不同）→ 中止重放"),
], x=0.62, y=1.46, w=12.1, h=5.2, size=11.5, gap=4)

# 36 恢復狀態機
s = content_slide("恢復狀態機：找不到就分級處理，而不是重試到死", "三級 + 熔斷，每一級都有明確代價")
rows = [
    ["級別", "手段", "配額", "代價"],
    ["L2A", "偵測頂層彈窗 → BACK 關閉", "1 次/目標", "低（可能關掉使用者想要的頁）"],
    ["L2B", "滾動尋路（先無障礙，再手勢）", "2 次/目標", "中（可能滾過頭）"],
    ["L3", "微教學：請使用者手動點一次", "3 次/任務", "高（打斷使用者）"],
    ["熔斷", "累計人工介入達上限 → 整任務停止", "全域", "—"],
]
table(s, rows, y=1.5, col_widths=[1.2, 5.0, 2.3, 3.6], size=11, row_h=0.52)
bullets(s, [
    ("H", "見底判定（避免無限滾動）"),
    ("1", "三個條件同時成立才算見底：頁面尾部簽名不變 + 底部節點位移 < 5px + 沒有新的滾動事件"),
    ("H", "節點黑名單（自癒）"),
    ("1", "連續點擊無效的節點進黑名單（同一個視窗內），下一輪改挑次佳候選，而不是原地重試 5 次"),
    ("1", "視窗切換時清空黑名單，避免跨頁誤殺"),
], x=0.62, y=4.5, w=12.1, h=1.9, size=11.5, gap=5)

# 37 部分完成
s = content_slide("部分完成策略：能做的先做完，做不到的明確說", "使用者要求：不能完成就直接停，部分不能完成就只停那一部分")
code_block(s, [
    "segments = groupByPackageHint(goals)      # 同一目標 App 的連續目標視為一段",
    "",
    "for (goal in goals):",
    "    ok = executeWithRecovery(goal)",
    "    if (!ok):",
    "        if (goal 與下一個目標同段)  → 停止整段，跳到下一段",
    "        else                        → 只跳過這一個目標，繼續下一個",
    "        failed.add(goal)",
    "",
    "pipelineOk = failed.isEmpty()",
], y=1.5, h=2.6, size=10.5)
bullets(s, [
    ("H", "為什麼要分段而不是全停"),
    ("1", "「打開 Gmail 搜尋 invoice，然後到設定關閉通知」——前半失敗不該拖累後半"),
    ("H", "三種結果都說清楚"),
    ("1", "全部成功：✓ 完成"),
    ("1", "部分成功：列出已完成與未完成，並說明未完成的原因"),
    ("1", "全部失敗：❌ 無法完成「目標」+ 原因，立即停止"),
], x=0.62, y=4.4, w=12.1, h=2.0, size=11.5, gap=5)

# ══════════════════════════════════════════════════════════════════════
# 38 PART VI
# ══════════════════════════════════════════════════════════════════════
section_slide("VI", "學習・資料・評測", "LEARNING, DATA & EVALUATION",
              ["姿態模型與遙測降權", "19 個資料庫與資料來源政策", "100 題評測、實測數據與除錯實錄"])

# 39 姿態模型
s = content_slide("姿態模型：12 維特徵 × 9 種頁面姿態", "用一個 12×9 的矩陣，取代對「這是什麼頁」的硬規則")
formula_panel(s, [
    "h = φ(features)            # 12 維特徵",
    "z = W·h + b                # W: 12×9",
    "p = softmax(z)             # 9 種姿態",
    "",
    "決策：argmax p；conf < 0.45 → 回退啟發式",
], x=0.62, y=1.5, w=6.0, h=2.5, size=11.5)
bullets(s, [
    ("H", "12 維特徵（全部可在無障礙樹上 O(n) 取得）"),
    ("1", "節點數、可點比例、可編輯數、可滾動數、列表容器數、卡片數"),
    ("1", "頂部是否有工具列、底部是否有導航、是否有輸入框、是否有彈窗、動態分數、捲動位置"),
    ("H", "9 種姿態"),
    ("1", "NAV_HOME、LIST_FEED、DETAIL、SEARCH_RESULTS、FORM_INPUT、MODAL_DIALOG、MEDIA、CHECKOUT、EMPTY"),
    ("H", "訓練方式"),
    ("1", "tools/train_posture_model.py：離線以標註資料訓練，匯出權重為資產；執行期先驗放在檔案系統，可熱替換"),
], x=6.85, y=1.5, w=5.85, h=4.6, size=10.5, gap=5)

# 40 資料庫
s = content_slide("資料庫全景：19 個 TSV，23 萬+ 列", "程式碼只留演算法，所有清單都在資料裡")
rows = [
    ["資料庫", "列數", "用途"],
    ["command_lexicon", "7,501", "動作詞、連接詞、參數槽（指令拆解的基礎）"],
    ["hit_cilin_synonyms / antonyms", "16,504 / 38,276", "確定性同義與互斥（避免把「播放」判成「付款」）"],
    ["opencc_zh_tw_cn", "43,758", "繁簡等價，讓兩岸用語都能命中"],
    ["google_material_icons", "4,267", "圖示 → 角色（圖標按鈕沒有文字時的唯二線索）"],
    ["aosp_seq2act_ui_settings", "5,908", "AOSP 設定頁的動作序列先驗"],
    ["zh_classifier / intent_slots / stopwords", "7,214 / 3,736 / 3,233", "量詞與代詞（判斷 CLASS 級目標）、槽位、停用詞"],
    ["ui_widget_capability_ontology", "56", "元件家族 × 能力 × 用途（本輪新增）"],
    ["ui_purpose_category_map", "14", "用途 → 設定分類（本輪新增）"],
    ["ui_value_adjust_lexicon", "142", "調高/降低/最大/一半 的方向與幅度（本輪新增）"],
]
table(s, rows, y=1.5, col_widths=[3.9, 2.2, 6.0], size=10, row_h=0.45)
tf = _tb(s, 0.62, 6.3, 12.1, 0.5)
_para(tf, "另有 chinese_encyclopedia_qa_corpus 120,000 列，供離線知識問答；不參與 GUI 決策。",
      size=10.5, color=C_MUTED, first=True, space_after=0)

# 41 評測
s = content_slide("評測體系：100 題、四個難度", "先能穩定重現，才談得上提升")
rows = [
    ["難度", "題數", "步數", "目標通過率", "實測（最近一次）"],
    ["EASY", "30", "3–8", "≥ 95%", "9/10 = 90%"],
    ["MEDIUM", "30", "8–15", "≥ 80%", "6/10 = 60%"],
    ["HARD", "25", "15–25", "≥ 60%", "尚未完整跑"],
    ["EXTREME", "15", "32–35", "≥ 30%", "尚未完整跑"],
]
table(s, rows, y=1.5, col_widths=[1.8, 1.4, 1.8, 2.6, 4.5], size=11, row_h=0.5)
bullets(s, [
    ("H", "判定規則（不看 UI 文字，避免自我欺騙）"),
    ("1", "以服務內部的 lastTaskSuccess 與 taskRunSeq 為準，輸出 WAE_EXEC taskResult=SUCCESS|FAIL seq=N"),
    ("1", "每題前強制停止目標 App（回到冷啟動），確保每次都是預設首頁"),
    ("1", "App 未安裝 → 記為 SKIP 而不是 FAIL，避免污染通過率"),
    ("H", "工具"),
    ("1", "tools/gen_benchmark_tasks.py 產生題庫；tools/run_benchmark.py 批次執行並輸出 TSV"),
    ("1", "App 內建開發版評測介面（可選難度與題數），發布前須移除"),
], x=0.62, y=4.3, w=12.1, h=2.1, size=10.5, gap=4)

# 42 除錯實錄
s = content_slide("除錯實錄：本輪修掉的 8 個真缺陷", "每一條都對應一次真機失敗，不是推測")
rows = [
    ["現象", "根因", "修法"],
    ["任務回報 SUCCESS 但畫面沒變", "進度條量程 0→0，拖曳被判定達標", "span ≤ 0 一律 false"],
    ["鑽到不相關區域", "選到無文字、無描述的容器照點", "要求「元件自身語意證據」"],
    ["值改成功卻算失敗", "以頁面簽名判定成效，滑桿不改變版面", "數值調整改以讀回值判定"],
    ["整頁候選都沒有文字", "快路徑分數全為 0，去重砍掉了文字節點", "去重條件 + 快路徑不去重"],
    ["連「顯示」都進不去", "空容器靠 isClickable 壓過文字節點", "同分優先有語意者"],
    ["亮度被判給「音效與震動」", "純語意相似度無法表達「屬性→分類」", "新增用途→分類表"],
    ["成功後仍繼續亂滑", "ADJUST 沒在成功時結束迴圈", "提前 return + 掃描僅前兩輪"],
    ["歷史計畫 expected=0 也執行", "MCTS 沒有把不確定計畫擋掉", "三重保險"],
]
table(s, rows, y=1.5, col_widths=[4.2, 4.6, 3.3], size=9.5, row_h=0.5)
tf = _tb(s, 0.62, 6.05, 12.1, 0.7)
_para(tf, "方法論：每個 bug 都先寫成可重現的單元測試，再修——因此測試數從 219 增加到 257。",
      size=11, color=RGBColor(0x0F, 0x76, 0x6E), bold=True, first=True, space_after=0)

# 43 設計巧思
s = content_slide("程式設計巧思 12 條", "這些決定讓系統在資源有限的手機上跑得穩")
cl = [
    ("不可變候選集", "CandidateOption 全程 copy-on-write，避免多執行緒共享可變狀態"),
    ("幀一致性檢查", "決策與執行比對 frameId，發現過期就丟棄重來"),
    ("短碼儲存", "動作以 5–14 bytes 短碼入庫，1000 步軌跡只佔 12KB"),
    ("TOCTOU 防護", "派發前節點 refresh + 位移 > 36px 就放棄"),
    ("事件驅動等待", "有內容變化事件就重置計時，不用固定 sleep"),
    ("指紋級黑名單", "以節點穩定雜湊為鍵，跨頁自動失效"),
    ("語意快取", "描述字串 → 向量結果 LRU 512，同頁命中率 > 60%"),
    ("成本感知感知層", "VoI 決定跑不跑模型，而不是固定全跑"),
    ("能力約束決策", "先問「這個元件能不能做」，再問「像不像」"),
    ("資料驅動詞表", "新增說法只改 TSV，不重編譯決策邏輯"),
    ("雙向查詢", "能力→元件、用途→元件，同一份資料兩種用法"),
    ("誠實失敗", "寧可回報無法完成，也不製造假進度"),
]
for i, (k, v) in enumerate(cl):
    col, row = i % 2, i // 2
    x = 0.62 + col * 6.15
    y = 1.46 + row * 0.83
    panel(s, x, y, 5.9, 0.72, fill=C_WHITE)
    _rect(s, x, y, 0.06, 0.72, C_ACCENT)
    tf = _tb(s, x + 0.18, y + 0.08, 5.6, 0.26)
    _para(tf, k, size=11, color=C_INK, bold=True, first=True, space_after=0)
    tf = _tb(s, x + 0.18, y + 0.36, 5.6, 0.3)
    _para(tf, v, size=9.5, color=C_MUTED, first=True, space_after=0)

# 44 風險與路線
s = content_slide("風險登記與未完成路線圖", "誠實列出還沒做到的事", tag="附錄")
bullets(s, [
    ("H", "已識別風險"),
    ("1", "自繪元件完全不回報 RangeInfo → 數值調整只能誠實失敗（不亂拖）"),
    ("1", "Android 會把同一個可見介面拆成多個 package → 不能用 package 名硬擋跨頁"),
    ("1", "冷啟動時無障礙樹可能是空殼 → 需要穩定閘門（已加，最長等 5 秒）"),
    ("H", "已設計、尚未接線"),
    ("1", "L:NOTIF 通知開啟（需 NotificationListenerService）"),
    ("1", "PICK_ONE 選單展開、EXPAND 收合：目前只用來排除錯誤候選，尚未作為獨立動作"),
    ("1", "分段式數值（+/− 按鈕）尚未做成「一次設到目標」的多步計畫"),
    ("1", "雙指縮放（PINCH）與圖片放大尚未實作"),
    ("H", "下一步優先序"),
    ("1", "① 把數值調整類任務補進評測集　② 音量／字體大小實測　③ 學習型 Twin 合成"),
], x=0.62, y=1.46, w=12.1, h=5.3, size=11.5, gap=5)

# 45 代碼地圖 + 封底
s = content_slide("代碼地圖與量測方式", "想驗證這份文件，從這些檔案開始", tag="附錄")
rows = [
    ["檔案", "角色"],
    ["WaeVisionAccessibilityService.kt", "主幹：感知、派發、等待、歸因、恢復（約 3,000 行）"],
    ["WaeV26PipelineOrchestrator.kt", "拆解、打分、決策、閘門、數值決策（約 1,300 行）"],
    ["core/component/*.kt", "元件能力圖譜、能力融合、數值語意與幾何（本輪新增）"],
    ["core/action/*.kt", "動作契約、滾動規劃、選擇器自癒、時序模型、輸入注入、遙測"],
    ["core/twin/*.kt", "世界模型、快路徑、序列重放、被動記錄"],
    ["core/decision/*.kt", "MCTS 規劃、VoI 調度"],
    ["core/safety/*.kt", "安全自動機與風險閘門"],
    ["assets/open_source_ai_databases/*.tsv", "19 個資料庫（本輪新增 3 個）"],
    ["docs/WAE_Atlas_Architecture.md", "本白皮書的完整文字版（含更多推導）"],
]
table(s, rows, y=1.5, col_widths=[5.0, 7.1], size=10, row_h=0.42)
panel(s, 0.62, 6.06, 12.1, 0.62, fill=RGBColor(0xEC, 0xFD, 0xF5), border=RGBColor(0x99, 0xF6, 0xE4))
tf = _tb(s, 0.86, 6.18, 11.7, 0.4)
_para(tf, "驗證指令：gradlew :app:testDebugUnitTest（257 項）｜ assembleDebug ｜ adb install -r + 重新啟用無障礙服務",
      size=10, color=RGBColor(0x0F, 0x51, 0x43), font=MONO, first=True, space_after=0)

out = os.path.join(os.getcwd(), "WAE_V26_Architecture_Whitepaper.pptx")
prs.save(out)
print("saved:", out, "slides:", len(prs.slides._sldIdLst))
