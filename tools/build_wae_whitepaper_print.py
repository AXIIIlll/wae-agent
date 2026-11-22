# -*- coding: utf-8 -*-
"""WAE V2.6 架構白皮書（列印精簡版）— 白底、淺色、20 頁、保留公式、無程式碼。

設計原則：
  * 全白背景，只用細線與淺灰面板，適合黑白列印
  * 不用大面積深色塊
  * 每頁一個主題：功能說明 + 公式（若有）
"""
import os
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR, MSO_AUTO_SIZE
from pptx.enum.shapes import MSO_SHAPE

# ── 色彩（淺色 / 列印友善）────────────────────────────────────────────
INK = RGBColor(0x11, 0x11, 0x11)       # 標題（近黑）
BODY = RGBColor(0x33, 0x33, 0x33)      # 內文
MUTED = RGBColor(0x6B, 0x6B, 0x6B)     # 註解
ACCENT = RGBColor(0x22, 0x22, 0x22)    # 小標（深灰）
ACCENT2 = RGBColor(0x55, 0x55, 0x55)   # 次要小標
LINE = RGBColor(0xBF, 0xBF, 0xBF)      # 一般分隔線（淺灰）
HEAD = RGBColor(0xFF, 0xFF, 0xFF)      # 表頭（白）
PANEL = RGBColor(0xFF, 0xFF, 0xFF)     # 面板（白）
PANEL2 = RGBColor(0xFF, 0xFF, 0xFF)    # 公式面板（白）

FONT = "Microsoft JhengHei"
MONO = "Consolas"

W, H = 13.333, 7.5
DECK = "WAE V2.6 架構白皮書"

prs = Presentation()
prs.slide_width = Inches(W)
prs.slide_height = Inches(H)
BLANK = prs.slide_layouts[6]
_page = [0]


def _tb(slide, x, y, w, h, wrap=True):
    box = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = box.text_frame
    tf.auto_size = MSO_AUTO_SIZE.NONE
    tf.word_wrap = wrap
    tf.margin_left = 0
    tf.margin_right = 0
    tf.margin_top = 0
    tf.margin_bottom = 0
    return tf


def _para(tf, text, size=13, color=BODY, bold=False, font=FONT, after=5, before=0,
          first=False, align=PP_ALIGN.LEFT, spacing=1.18):
    p = tf.paragraphs[0] if first else tf.add_paragraph()
    p.alignment = align
    p.space_after = Pt(after)
    p.space_before = Pt(before)
    p.line_spacing = spacing
    r = p.add_run()
    r.text = text
    r.font.size = Pt(size)
    r.font.bold = bold
    r.font.name = font
    r.font.color.rgb = color
    return p


def hline(slide, x, y, w, color=LINE, width=0.75):
    c = slide.shapes.add_connector(1, Inches(x), Inches(y), Inches(x + w), Inches(y))
    c.line.color.rgb = color
    c.line.width = Pt(width)


def panel(slide, x, y, w, h, fill=PANEL, border=LINE):
    shp = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(x), Inches(y), Inches(w), Inches(h))
    shp.fill.solid()
    shp.fill.fore_color.rgb = fill
    shp.line.color.rgb = border
    shp.line.width = Pt(0.75)
    shp.shadow.inherit = False
    _strip_style(shp)
    return shp


def accent_bar(slide, x, y, w, h, color=ACCENT):
    shp = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(x), Inches(y), Inches(w), Inches(h))
    shp.fill.solid()
    shp.fill.fore_color.rgb = color
    shp.line.fill.background()
    shp.shadow.inherit = False
    _strip_style(shp)
    return shp


def footer(slide):
    _page[0] += 1
    hline(slide, 0.62, 7.02, 12.09)
    tf = _tb(slide, 0.62, 7.09, 9.0, 0.26)
    _para(tf, DECK, size=8.5, color=MUTED, first=True, after=0)
    tf = _tb(slide, 11.6, 7.09, 1.11, 0.26)
    _para(tf, f"{_page[0]:02d} / 20", size=8.5, color=MUTED, first=True, after=0, align=PP_ALIGN.RIGHT)


def page(title, lead=None):
    slide = prs.slides.add_slide(BLANK)
    accent_bar(slide, 0.62, 0.52, 0.09, 0.42)
    tf = _tb(slide, 0.86, 0.44, 11.6, 0.52)
    _para(tf, title, size=24, color=INK, bold=True, first=True, after=0)
    if lead:
        tf = _tb(slide, 0.87, 0.99, 11.7, 0.3)
        _para(tf, lead, size=10.5, color=MUTED, first=True, after=0)
        hline(slide, 0.62, 1.36, 12.09)
        top = 1.5
    else:
        hline(slide, 0.62, 1.12, 12.09)
        top = 1.26
    footer(slide)
    return slide, top


def bullets(slide, items, x=0.62, y=1.5, w=12.09, h=5.2, size=12.5, gap=5):
    tf = _tb(slide, x, y, w, h)
    first = True
    for it in items:
        kind, text = it if isinstance(it, tuple) else ("B", it)
        if kind == "H":
            _para(tf, text, size=size + 0.5, color=INK, bold=True, first=first,
                  before=0 if first else 7, after=4)
        elif kind == "M":
            _para(tf, text, size=size - 0.5, color=ACCENT, bold=True, font=MONO,
                  first=first, after=gap)
        elif kind == "N":
            _para(tf, "　" + text, size=size - 0.5, color=MUTED, first=first, after=gap)
        else:
            _para(tf, "・" + text, size=size, color=BODY, first=first, after=gap)
        first = False
    return tf



def _strip_style(shp):
    """移除形狀的 p:style（主題參考），否則部分渲染器仍會套用陰影。"""
    from pptx.oxml.ns import qn
    el = shp._element
    for child in el.findall(qn("p:style")):
        el.remove(child)


def _cell_border(cell, color="BFBFBF", w=6350):
    """對儲存格四邊加上細灰線（直接寫 XML，python-pptx 沒有公開 API）。"""
    from pptx.oxml.ns import qn
    tcPr = cell._tc.get_or_add_tcPr()
    for e in ("B", "T", "R", "L"):
        tag = qn("a:ln%s" % e)
        for old in tcPr.findall(tag):
            tcPr.remove(old)
        ln = tcPr.makeelement(tag, {"w": str(w), "cap": "flat", "cmpd": "sng", "algn": "ctr"})
        fill = ln.makeelement(qn("a:solidFill"), {})
        clr = fill.makeelement(qn("a:srgbClr"), {"val": color})
        fill.append(clr)
        ln.append(fill)
        tcPr.insert(0, ln)


def note(slide, text, y, x=0.62, w=12.09, size=8.8):
    """補充說明（灰字）：用來說明符號或容易被誤解的地方。"""
    tf = _tb(slide, x, y, w, 0.5)
    _para(tf, text, size=size, color=MUTED, first=True, after=0, spacing=1.25)


def formula(slide, lines, x=0.62, y=1.5, w=12.09, h=1.5, title=None, size=12):
    shp = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(x), Inches(y), Inches(w), Inches(h))
    shp.fill.solid()
    shp.fill.fore_color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
    shp.line.color.rgb = RGBColor(0x99, 0x99, 0x99)
    shp.line.width = Pt(0.75)
    shp.shadow.inherit = False
    _strip_style(shp)
    accent_bar(slide, x, y, 0.035, h, RGBColor(0x11, 0x11, 0x11))
    yy = y + 0.13
    if title:
        tf = _tb(slide, x + 0.2, yy, w - 0.38, 0.24)
        _para(tf, title, size=9, color=MUTED, bold=True, font=MONO, first=True, after=0)
        yy += 0.27
    tf = _tb(slide, x + 0.2, yy, w - 0.4, h - (yy - y) - 0.07)
    first = True
    for ln in lines:
        _para(tf, ln, size=size, color=INK, font=MONO, first=first, after=2.5, spacing=1.05)
        first = False


def table(slide, rows, x=0.62, y=1.5, w=12.09, widths=None, size=10.5,
          row_h=0.36, header_size=None, first_col_bold=False):
    nr, nc = len(rows), len(rows[0])
    shp = slide.shapes.add_table(nr, nc, Inches(x), Inches(y), Inches(w), Inches(row_h * nr))
    tbl = shp.table
    from pptx.oxml.ns import qn
    tblPr = tbl._tbl.tblPr
    for el in tblPr.findall(qn("a:tableStyleId")):
        tblPr.remove(el)
    sid = tblPr.makeelement(qn("a:tableStyleId"), {})
    sid.text = "{2D5ABB26-0587-4C30-8999-92F81FD0307C}"  # No Style, No Grid
    tblPr.append(sid)
    for attr in ("firstRow", "bandRow", "firstCol"):
        if attr in tblPr.attrib:
            del tblPr.attrib[attr]
    if widths:
        total = sum(widths)
        for i, cw in enumerate(widths):
            tbl.columns[i].width = Emu(int(Inches(w) * cw / total))
    for r in range(nr):
        tbl.rows[r].height = Inches(row_h)
        for c in range(nc):
            cell = tbl.cell(r, c)
            cell.margin_left = Inches(0.08)
            cell.margin_right = Inches(0.06)
            cell.margin_top = Inches(0.02)
            cell.margin_bottom = Inches(0.02)
            cell.vertical_anchor = MSO_ANCHOR.MIDDLE
            cell.fill.solid()
            cell.fill.fore_color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
            _cell_border(cell, "BFBFBF" if r > 0 else "8C8C8C", 6350 if r > 0 else 12700)
            tf = cell.text_frame
            tf.word_wrap = True
            para = tf.paragraphs[0]
            para.alignment = PP_ALIGN.LEFT
            para.line_spacing = 1.05
            run = para.add_run()
            run.text = str(rows[r][c])
            run.font.name = FONT
            run.font.size = Pt(header_size or size if r == 0 else size)
            run.font.bold = (r == 0) or (first_col_bold and c == 0)
            run.font.color.rgb = INK if r == 0 else BODY
    return tbl


def cards(slide, items, cols=3, x0=0.62, y0=1.5, cw=3.86, ch=2.1, gapx=0.22, gapy=0.24):
    for i, (num, name, desc) in enumerate(items):
        col, row = i % cols, i // cols
        x = x0 + col * (cw + gapx)
        y = y0 + row * (ch + gapy)
        panel(slide, x, y, cw, ch, fill=RGBColor(0xFF, 0xFF, 0xFF))
        accent_bar(slide, x, y, cw, 0.05)
        tf = _tb(slide, x + 0.2, y + 0.2, 0.55, 0.3)
        _para(tf, num, size=11, color=RGBColor(0x99, 0x99, 0x99), bold=True, font=MONO,
              first=True, after=0)
        tf = _tb(slide, x + 0.78, y + 0.18, cw - 0.95, 0.32)
        _para(tf, name, size=13.5, color=INK, bold=True, first=True, after=0)
        tf = _tb(slide, x + 0.2, y + 0.68, cw - 0.4, ch - 0.85)
        _para(tf, desc, size=10.5, color=BODY, first=True, after=0, spacing=1.3)


def layers(slide, items, x=0.62, y=1.5, w=7.6):
    hh, gap = 0.66, 0.1
    top = y
    for i, (code, name, desc) in enumerate(items):
        panel(slide, x, top, w, hh, fill=RGBColor(0xFF, 0xFF, 0xFF))
        accent_bar(slide, x, top, 0.06, hh, ACCENT if i % 2 == 0 else ACCENT2)
        tf = _tb(slide, x + 0.2, top + 0.14, 0.9, 0.34)
        _para(tf, code, size=12, color=ACCENT, bold=True, font=MONO, first=True, after=0)
        tf = _tb(slide, x + 1.12, top + 0.09, w - 1.28, 0.28)
        _para(tf, name, size=11.5, color=INK, bold=True, first=True, after=0)
        tf = _tb(slide, x + 1.12, top + 0.36, w - 1.28, 0.26)
        _para(tf, desc, size=8.8, color=MUTED, first=True, after=0)
        top += hh + gap


# ══ 01 封面 ═══════════════════════════════════════════════════════════
s = prs.slides.add_slide(BLANK)
accent_bar(s, 0.62, 1.55, 2.4, 0.055, ACCENT)
tf = _tb(s, 0.62, 1.78, 8.6, 0.4)
_para(tf, "ANDROID GUI AGENT ・ 技術架構白皮書", size=10.5, color=ACCENT, bold=True,
      font=MONO, first=True, after=0)
tf = _tb(s, 0.6, 2.22, 9.4, 1.3)
_para(tf, "WAE V2.6", size=46, color=INK, bold=True, first=True, after=2)
tf = _tb(s, 0.62, 3.42, 9.4, 0.7)
_para(tf, "全管線架構白皮書", size=26, color=INK, bold=True, first=True, after=0)
tf = _tb(s, 0.62, 4.3, 7.4, 0.9)
_para(tf, "無障礙服務驅動 · 離線推論 · 資料驅動 · 可審計決策",
      size=12.5, color=BODY, first=True, after=4)
_para(tf, "L0 直達 → L1 感知 → L1.5 語意 → L3 決策 → L4 執行 → L5 歸因",
      size=10, color=MUTED, first=False, after=0)
hline(s, 0.62, 5.32, 6.4)
tf = _tb(s, 0.62, 5.46, 6.4, 0.9)
_para(tf, "版本 2.6 ｜ 2026 年 11 月 ｜ 內部技術文件 ｜ 共 20 頁",
      size=9.5, color=MUTED, first=True, after=0)
panel(s, 8.1, 1.9, 4.6, 3.5, fill=RGBColor(0xFF, 0xFF, 0xFF))
tf = _tb(s, 8.32, 2.06, 4.2, 0.3)
_para(tf, "系統快照", size=10, color=ACCENT, bold=True, first=True, after=0)
snap = [
    ("動作原語", "3 類（LAUNCH / INTERACT / INPUT）"),
    ("元件能力家族", "56 個 × 20 種能力"),
    ("知識資料表", "19 個 · 逾 23 萬列"),
    ("離線模型", "MiniLM INT8 · YOLOv8 · PP-OCRv4"),
    ("單元測試", "257 項 · 0 失敗"),
]
yy = 2.44
for k, v in snap:
    tf = _tb(s, 8.32, yy, 1.5, 0.24)
    _para(tf, k, size=8.5, color=MUTED, first=True, after=0)
    tf = _tb(s, 8.32, yy + 0.22, 4.15, 0.28)
    _para(tf, v, size=10.5, color=INK, bold=True, first=True, after=0)
    yy += 0.58
_page[0] += 1

# ══ 02 產品目的與定位 ═════════════════════════════════════════════════
s, top = page("產品目的：它要解決什麼問題",
              "一句中文，代替使用者完成跨 App 的多步操作")
bullets(s, [
    ("H", "核心目的"),
    ("B", "使用者說「去 Gmail 搜尋 invoice 並點開第一封」，Agent 自己看畫面、自己判斷、自己點"),
    ("B", "不需要寫腳本、不需要連電腦、不需要 root；所有理解都在手機本機完成"),
    ("H", "三個使用價值"),
    ("B", "通用：同一套能力面對任意 App，不為個別 App 寫流程"),
    ("B", "可解釋：每個決定都留下理由（分數、門檻、命中詞），事後可以追"),
    ("B", "可信任：不確定就停下來並說明原因，不用亂點製造假進度"),
    ("H", "明確的邊界"),
    ("N", "不是自動化腳本：沒有寫死座標與流程　｜　不是雲端 Agent：無網路權限，不上傳畫面"),
    ("N", "不是破解工具：不解析 APK / DEX，只使用 Android 公開的無障礙介面"),
], y=top, size=12, gap=4)
panel(s, 8.9, top, 3.8, 2.6, fill=RGBColor(0xFF, 0xFF, 0xFF))
tf = _tb(s, 9.1, top + 0.16, 3.4, 0.3)
_para(tf, "衡量成功的三個數字", size=10, color=ACCENT, bold=True, first=True, after=0)
bullets(s, [
    ("B", "任務成功率：四種難度分開計算"),
    ("B", "每步延遲：目標 < 1.2 秒"),
    ("B", "誤點率：錯點到不相關元件的比例"),
    ("B", "誠實率：做不到時明確說出的比例"),
], x=9.1, y=top + 0.52, w=3.4, h=2.5, size=10, gap=6)

# ══ 03 設計哲學 ═══════════════════════════════════════════════════════
s, top = page("六條設計哲學", "後面所有技術決策，都可以回溯到這六條")
cards(s, [
    ("01", "誠實失敗", "做不到就明確說「無法完成」並停止；寧可少做一步，也不製造假進度。"),
    ("02", "資料驅動", "App 清單、元件能力、詞表全部放資料表；程式只保留演算法，方便後期維護。"),
    ("03", "綠色通道", "只用公開 SDK 與無障礙節點的公開欄位，並用自動化測試強制檢查。"),
    ("04", "可審計", "每個決策都印出理由：候選分數、門檻、命中詞、最終判定。"),
    ("05", "低延遲", "感知模組依「資訊增益」調度，資訊足夠就不跑模型。"),
    ("06", "跨 App 泛化", "以「元件能力 + 語意用途」取代「某個 App 的某個按鈕」。"),
], y0=top, cw=3.86, ch=2.35)

# ══ 04 系統分層 ═══════════════════════════════════════════════════════
s, top = page("系統分層：L0–L5", "每一層只做一件事，跨層只傳不可變資料")
layers(s, [
    ("L0", "直達層 Deep Link", "已匯入的能力規則 → 直接開啟目標頁，省下 5–8 步"),
    ("L1", "感知層 Perception", "節點採集 + 語境焊接 + 六維閘門 + 視覺／OCR 調度"),
    ("L1.5", "語意層 Semantics", "詞林同義詞 + 文字向量（384 維）+ 混合打分"),
    ("L3", "決策層 Decision", "機率分布 + 三條件門檻 + 歧義分流 + 能力約束"),
    ("L4", "執行層 Action", "過期防護、動作契約、手勢／輸入降級鏈"),
    ("L5", "歸因層 Attribution", "頁面簽名比對、三態因果判定、恢復與熔斷"),
], y=top, w=7.6)
panel(s, 8.5, top, 4.2, 4.4, fill=RGBColor(0xFF, 0xFF, 0xFF))
tf = _tb(s, 8.72, top + 0.16, 3.8, 0.3)
_para(tf, "跨層的三個不變量", size=10.5, color=ACCENT, bold=True, first=True, after=0)
bullets(s, [
    ("B", "不可變資料：候選項一旦建立就不再修改"),
    ("B", "幀一致性：決策與執行必須同一畫面幀，否則重來"),
    ("B", "單一真相：所有模組讀同一棵樹、同一份資料"),
], x=8.72, y=top + 0.5, w=3.8, h=3.4, size=10, gap=7)

# ══ 05 任務生命週期 ═══════════════════════════════════════════════════
s, top = page("一次任務的 11 個階段", "從一句中文到最後一個手勢；每階段都有明確產出")
steps = [
    ("1", "指令拆解", "連接詞切分 → 動作詞匹配 → 目標序列"),
    ("2", "App 解析", "別名比對資料庫 → 取得套件名"),
    ("3", "啟動／直達", "深鏈或啟動 App；開啟型目標在此完成"),
    ("4", "感知", "採集 → 焊接 → 閘門 → 候選集"),
    ("5", "語意打分", "詞林 + 向量 + 角色一致性"),
    ("6", "決策", "機率分布 + 三條件 → 選定／澄清／拒絕"),
    ("7", "執行動作", "契約冪等檢查 → 節點刷新 → 派發"),
    ("8", "等待穩定", "事件驅動靜默 + 該控件歷史上限"),
    ("9", "歸因", "前後頁面簽名比對 → 三態判定"),
    ("10", "恢復", "去彈窗／滾動尋路／請使用者示範"),
    ("11", "收斂", "寫入世界模型與遙測；失敗依策略前進或停止"),
]
for col, group in enumerate((steps[:6], steps[6:])):
    x = 0.62 + col * 6.2
    y = top
    for num, name, desc in group:
        panel(s, x, y + 0.02, 0.4, 0.4, fill=RGBColor(0xFF, 0xFF, 0xFF), border=LINE)
        tf = _tb(s, x, y + 0.11, 0.4, 0.26)
        _para(tf, num, size=9.5, color=ACCENT, bold=True, font=MONO, first=True,
              after=0, align=PP_ALIGN.CENTER)
        tf = _tb(s, x + 0.54, y + 0.02, 5.5, 0.26)
        _para(tf, name, size=11.5, color=INK, bold=True, first=True, after=0)
        tf = _tb(s, x + 0.54, y + 0.29, 5.5, 0.3)
        _para(tf, desc, size=9, color=MUTED, first=True, after=0)
        y += 0.72
formula(s, [
    "總延遲目標 < 1.2 秒／步　=　感知 ≤ 220ms ＋ 打分 ≤ 90ms ＋ 決策 ≤ 30ms ＋ 動作與等待 ≤ 900ms",
], y=6.36, h=0.5, size=10.5)

# ══ 06 感知採集 ═══════════════════════════════════════════════════════
s, top = page("感知層：把節點樹變成「帶語境的候選」", "看得見、看得懂，才有機會做對")
bullets(s, [
    ("H", "採集規則"),
    ("B", "深度優先、上限 480 個節點：避免異常頁面造成卡頓"),
    ("B", "只留可見且有面積的節點；被鍵盤遮住的一律丟棄"),
    ("B", "收集子節點文字摘要（最多 6 個），作為父節點的語境"),
    ("H", "語境焊接：解決「圖標按鈕沒有文字」"),
    ("B", "把「位置區域 + 容器角色 + 元件角色 + 鄰近文字」組成一句描述"),
    ("N", "例：CENTER_LIST ｜ LIST_ITEM ｜ search ｜ 搜尋"),
    ("B", "這句描述同時餵給詞林比對與向量模型，讓沒有文字的按鈕也「說得出自己是誰」"),
    ("H", "容器角色推導（供後續排除誤點）"),
    ("B", "清單容器 → 列表項　｜　工具列 → 工具列　｜　底部導覽／分頁 → 導覽列"),
], y=top, size=11.5, gap=4)
formula(s, [
    "焊接描述 = 〔空間區域〕+〔父容器角色〕+〔元件角色〕+〔自身或鄰近文字〕",
], y=5.95, h=0.62, size=11)

# ══ 07 六維閘門 ═══════════════════════════════════════════════════════
s, top = page("六維可操作閘門", "在花任何算力之前，先濾掉不可能操作的節點")
formula(s, [
    "可操作 ⇔ 可見 ∧ 啟用 ∧ 尺寸 ≥ 8dp ∧ 覆蓋率 ≥ 0.50",
    "        ∧ 未被遮擋 ∧ 不在黑名單",
    "",
    "覆蓋率 = |B ∩ V| / |A|",
    "  B = 節點邊界　V = 可視區　A = 節點面積",
], x=0.62, y=top, w=6.2, h=2.5, size=11)
bullets(s, [
    ("H", "六個維度各自擋掉什麼"),
    ("B", "可見性：背景頁、被覆蓋的節點"),
    ("B", "啟用狀態：變灰的按鈕"),
    ("B", "尺寸：分隔線、裝飾點等雜訊"),
    ("B", "覆蓋率：被螢幕邊緣裁掉一半的元素"),
    ("B", "遮擋：彈窗後方的頁面"),
    ("B", "黑名單：連續點擊無效的節點"),
], x=7.1, y=top, w=5.6, h=2.4, size=10.5, gap=4)
panel(s, 0.62, 4.32, 12.09, 1.9, fill=RGBColor(0xFF, 0xFF, 0xFF))
tf = _tb(s, 0.86, 4.46, 11.6, 0.3)
_para(tf, "為什麼先用幾何、不用模型", size=11, color=ACCENT, bold=True, first=True, after=0)
tf = _tb(s, 0.86, 4.82, 11.6, 1.2)
_para(tf, "幾何判斷是常數時間且零誤差；模型有延遲、也可能幻覺。能被幾何否定的候選，"
          "就不該浪費 16 毫秒去做文字向量。實測：一個繁忙頁面 100 多個節點，"
          "通過此閘門後平均只剩 21 個進入打分，這是延遲能壓在 1 秒內的主因之一。",
      size=10.5, color=BODY, first=True, after=0, spacing=1.35)

# ══ 08 元件能力圖譜 ═══════════════════════════════════════════════════
s, top = page("元件能力圖譜：把「是什麼」升級為「能做什麼」",
              "只用文字相似度，永遠分不出滑桿與說明文字")
bullets(s, [
    ("H", "兩條互相獨立的軸"),
    ("B", "能力軸：由「元件類別」與「運行期語意訊號」決定，例如滑桿天生可設定數值、可拖曳"),
    ("B", "用途軸：由文字、內容描述、圖示語意、頁面語境決定，例如同一根滑桿可以調亮度或音量"),
    ("B", "動作 = 能力 ∩ 用途：兩軸相乘，才知道該對它做什麼"),
], y=top, size=11, gap=4)
rows = [
    ["元件家族", "代表元件", "具備能力", "常見用途"],
    ["滑桿", "SeekBar / Slider / SeekBarPreference", "設定數值、拖曳、水平捲動", "亮度、音量、字體大小、播放進度"],
    ["開關", "Switch / SwitchCompat / SwitchPreference", "切換（二態）", "功能啟用、通知、深色模式"],
    ["進度條", "ProgressBar / 線性進度指示器", "唯讀（不可操作）", "載入中、完成度、已用額度"],
    ["下拉選單", "Spinner / ListPreference", "點開、單選", "語言、地區、排序、畫質"],
    ["清單容器", "RecyclerView / ListView", "垂直／水平捲動、選取", "信件、商品、搜尋結果"],
    ["數值選擇", "NumberPicker / DatePicker / TimePicker", "設定數值、步進、垂直捲動", "數量、日期、時間"],
]
table(s, rows, y=3.15, widths=[1.6, 4.3, 3.5, 2.7], size=9.5, row_h=0.4, first_col_bold=True)
formula(s, [
    "動作 = 能力(元件) ∩ 用途(語意)　｜　資料規模：56 個元件家族 × 20 種能力，全部在資料表內",
], y=6.2, h=0.6, size=10.5)

# ══ 09 能力融合 ═══════════════════════════════════════════════════════
s, top = page("能力融合：先驗永遠要讓位給實證",
              "自繪元件（Compose、Flutter、自訂滑桿）只有運行期訊號說得出真相")
rows = [
    ["運行期訊號（公開欄位）", "提升的能力", "處理原則"],
    ["數值範圍存在且量程 > 0", "設定數值、拖曳", "互動型移除「唯讀」；唯讀型（進度條）保留"],
    ["動作清單含「設定進度」", "設定數值（強證據）", "信心值提升至 0.95"],
    ["可勾選 / 已勾選", "切換", "開關與勾選走切換語意"],
    ["輸入型別為文字", "文字輸入", "移除唯讀標記"],
    ["集合資訊 rows / columns", "垂直 / 水平捲動", "單列多欄 → 水平；其餘 → 垂直"],
    ["展開 / 選取動作", "展開、單選", "為選單與手風琴鋪路"],
]
table(s, rows, y=top, widths=[4.0, 2.6, 5.5], size=10, row_h=0.46)
formula(s, [
    "能力(元件) = 類別先驗 ∪ 運行期訊號 ∖ 唯讀（當節點可互動時）",
    "可調整 ⇔ 具備「設定數值」 ∧ 未標記為唯讀",
], y=4.86, h=1.0, size=11)
tf = _tb(s, 0.62, 6.02, 12.09, 0.9)
_para(tf, "設計重點：先驗只是起點，不是結論。所有能力都必須通過運行期證據的檢驗，"
          "因此同一個畫面在原生元件與自繪元件的 App 上，能歸類出相同的行為。",
      size=10.5, color=MUTED, first=True, after=0, spacing=1.3)

# ══ 10 用途→分類與配對 ═══════════════════════════════════════════════
s, top = page("用途 → 分類，以及「標籤 ↔ 滑桿」的配對",
              "一個是常識，一個是幾何；兩者缺一就會亂鑽或選不到")
panel(s, 0.62, top, 5.9, 1.6, fill=RGBColor(0xFF, 0xFF, 0xFF), border=RGBColor(0xA6, 0xA6, 0xA6))
tf = _tb(s, 0.84, top + 0.14, 5.5, 0.3)
_para(tf, "實際失敗：純語意分不出分類", size=10.5, color=ACCENT2, bold=True, first=True, after=0)
tf = _tb(s, 0.84, top + 0.5, 5.5, 1.7)
_para(tf, "「把亮度調高」的語意分：音效與震動 0.59、已連接的裝置 0.58、顯示螢幕 0.58。"
          "三者幾乎同分，模型無法表達「屬性住在哪個分類」這種常識。",
      size=10.5, color=BODY, first=True, after=0, spacing=1.3)
bullets(s, [
    ("H", "解法一：14 列的分類關聯表"),
    ("B", "顯示 → 亮度｜字體大小｜顯示大小｜逾時｜深色模式"),
    ("B", "音效與震動 → 音量｜震動強度｜鈴聲｜通知音"),
    ("B", "決策時先查分類，只在该分類的入口列中挑選"),
], x=6.9, y=top, w=5.8, h=2.3, size=10.5, gap=4)
formula(s, [
    "配對規則：包含 → 正下方（間距 ≤ 200dp，水平重疊 ≥ 30%）→ 同列右側（垂直差 ≤ 28dp，間距 ≤ 240dp）",
    "只在兩個候選差距 ≥ 0.05 時才往下鑽；否則回報「無法判斷」而不是硬選",
], y=4.1, h=1.0, size=10.5)
bullets(s, [
    ("H", "為什麼標籤與滑桿要配對"),
    ("B", "滑桿本身沒有文字，語意分永遠是 0；而「亮度」有分卻不可操作"),
    ("B", "用幾何關係把兩者連起來，才是真的「知道該拖哪一根」"),
], y=5.35, w=12.09, size=10.5, gap=4)

# ══ 11 VoI ════════════════════════════════════════════════════════════
s, top = page("VoI：把感知模型當成「有成本的服務」", "不是每頁都跑模型；只在值得的時候花算力")
formula(s, [
    "max   Σ ΔEIG(m)          s.t.   Σ cost(m) ≤ B",
    " m∈S                     m∈S",
    "",
    "ΔEIG(m) ≈ 1 − max(p₁, p₂)　　p 由上一輪分布取得；差距越小，資訊越值錢",
    "成本：圖示檢測 180ms ｜ 文字辨識 900ms ｜ 預算 1200ms（強制 2000ms）",
], x=0.62, y=top, w=7.3, h=2.1, size=10.5)
bullets(s, [
    ("H", "三個實測決策"),
    ("B", "候選充足且文字齊全 → 兩個模型都不跑（省 1 秒以上）"),
    ("B", "候選稀疏或沒有文字 → 兩個都跑"),
    ("B", "決策結果是「拒絕」或「歧義」且尚未升級 → 補跑一次辨識"),
    ("B", "每個任務只升級一次，避免每輪重跑的成本災難"),
], x=8.2, y=top, w=4.5, h=2.2, size=10, gap=4)
panel(s, 0.62, 4.0, 12.09, 2.2, fill=RGBColor(0xFF, 0xFF, 0xFF))
tf = _tb(s, 0.86, 4.14, 11.6, 0.3)
_para(tf, "兩個視覺通道的分工", size=11, color=ACCENT, bold=True, first=True, after=0)
bullets(s, [
    ("B", "圖示檢測（單類別目標偵測）：找出所有「看起來像按鈕」的框，處理無障礙樹是空的頁面"),
    ("B", "文字辨識（偵測 + 辨識兩階段）：把畫面文字變成候選，處理 WebView 與自繪文字"),
    ("B", "兩者的候選都會與無障礙節點焊接；沒有實體節點的候選只能點擊，不能讀寫屬性"),
], x=0.86, y=4.5, w=11.6, h=1.6, size=10, gap=4)

# ══ 12 語意與混合打分 ═════════════════════════════════════════════════
s, top = page("語意理解：確定性同義詞 + 向量近似", "兩種訊號取最大值，既不失準也不會互相拖累")
formula(s, [
    "相似度  sim(a, b) = (a · b) / (‖a‖₂ · ‖b‖₂)　　（384 維，INT8 量化）",
    "",
    "混合分 hybrid = clamp( max( s_資料庫 , s_向量 , s_規則 ) , 0, 1 )",
    "  s_資料庫：詞林同義與動作詞角色一致 → 0.84 ~ 0.86",
    "  s_向量　：文字向量餘弦相似度 → 0 ~ 1",
    "  s_規則　：明確規則（例如識別碼含 search）→ 0.70 ~ 0.90",
], y=top, w=12.09, h=2.1, size=10.5)
bullets(s, [
    ("B", "同義詞庫（1.65 萬組）負責「確定性同義」：搜尋 ≈ 查找 ≈ 尋找，不會被模型幻覺帶偏"),
    ("B", "向量負責「模糊近似」：亮度 ≈ 螢幕亮度，補上詞庫沒收錄的口語說法"),
    ("B", "兩者取最大值，任何一邊命中就夠；避免「詞庫不認識就完全失效」"),
], y=4.05, size=10.5, gap=4)
formula(s, [
    "打字類目標若不可操作 ×0.75　｜　唯讀元件被當點擊目標 ×0.75　｜　歷史成功率 < 60% 再 −0.15",
], y=5.42, h=0.6, size=10)
tf = _tb(s, 0.62, 6.12, 12.09, 0.8)
_para(tf, "這三個修正都來自真機失敗：讓「含關鍵詞的說明文字」不再搶走輸入框、"
          "讓進度條不再被點、讓老是失敗的選擇器自動退位。",
      size=10, color=MUTED, first=True, after=0, spacing=1.3)

# ══ 13 L3 決策 ═══════════════════════════════════════════════════════
s, top = page("決策：不是「取最高分」，而是「夠不夠格」", "三個門檻同時通過才算選定")
formula(s, [
    "zᵢ = w·φ(目標, 候選ᵢ) + sᵢ + bᵢ",
    "pᵢ = exp(zᵢ / τ) / Σⱼ exp(zⱼ / τ)",
    "",
    "選定（CLEAR_WIN） ⇔  p₁ ≥ θ_絕對(n)",
    "                    ∧ (p₁ − p₂) ≥ θ_差距(n)",
    "                    ∧ sim₁ ≥ θ_相似下限",
], x=0.62, y=top, w=6.3, h=2.55, size=11)
note(s, "註：τ＝溫度（越大越平）；θ＝門檻（隨候選數 n 調整）；φ＝特徵向量、w＝權重。", y=top + 2.62, w=6.3)
bullets(s, [
    ("H", "三個門檻各自防什麼"),
    ("B", "絕對信心：候選多時分布被攤薄，門檻隨候選數 n 調整"),
    ("B", "與第二名的差距：避免 0.31 對 0.30 的硬選"),
    ("B", "語意下限：避免全部候選都很低時仍硬選一個"),
    ("H", "溫度 τ 的取捨"),
    ("B", "調 τ 會同時影響所有情境；因此改為讓門檻隨 n 變動，比較好除錯"),
], x=7.2, y=top, w=5.5, h=2.5, size=10, gap=4)
tf = _tb(s, 0.62, 4.42, 11.6, 0.3)
_para(tf, "三種輸出與各自的後續", size=11, color=ACCENT, bold=True, first=True, after=0)
bullets(s, [
    ("B", "選定：直接派發動作，並在派發前做一次冪等檢查"),
    ("B", "歧義：停在原地詢問使用者，不進入滾動恢復（避免把澄清變成亂點）"),
    ("B", "拒絕：依序嘗試去彈窗、滾動尋路、請使用者示範；三級配額用完就結束"),
], x=0.62, y=4.78, w=11.6, h=1.4, size=10, gap=4)

# ══ 14 歧義與 CLASS ═══════════════════════════════════════════════════
s, top = page("歧義處理與「類別級目標」", "寧可問一次，也不要錯一輩子")
rows = [
    ["情況", "判定條件", "處理方式"],
    ["明確歧義", "前兩名機率都高、差距小，且兩者都能操作", "列出前三名請使用者選，不進入恢復流程"],
    ["近同分", "兩者語意分 ≥ 0.80、差距 < 0.03，且不是同一標籤", "強制澄清，避免硬選"],
    ["類別級目標", "使用者說「隨便一個」「第一封」", "在內容元素中依位置取第一個，不走語意競賽"],
    ["內容為空", "搜尋結果頁沒有任何內容元素", "回報「查無結果」並立即停止"],
    ["語意不可信", "所有候選相似度都低於 0.20", "回報「此頁找不到對應目標」，不亂點"],
]
table(s, rows, y=top, widths=[1.9, 5.3, 4.9], size=9.5, row_h=0.52, first_col_bold=True)
bullets(s, [
    ("H", "類別級目標的回退邏輯（實作原則）"),
    ("B", "先把「不是內容」的排除：搜尋框、輸入框、底部導航、工具列"),
    ("B", "再依序：列表項／卡片容器 → 語意分 → 面積 → 最上方者（保證可重現）"),
], y=4.75, size=10.5, gap=4)

# ══ 15 數值調整 ═══════════════════════════════════════════════════════
s, top = page("數值調整：設定瞄準值，而不是「點一下」", "亮度、音量、字體大小、進度拖曳都吃這一套")
bullets(s, [
    ("H", "四種形態都支援"),
    ("B", "連續滑桿 → 直接設定值或拖曳　｜　＋／－ 按鈕 → 連續點擊"),
    ("B", "下拉選項 → 點開再選　｜　數字選擇器 → 步進或直接輸入"),
    ("H", "決策順序"),
    ("B", "有可調元件 → 由「標籤↔滑桿」配對決定目標，直接設定"),
    ("B", "沒有可調元件 → 依用途分類只鑽一層（例如亮度 → 顯示）；差距不足 0.05 就停"),
    ("B", "兩者都沒有 → 明確拒絕，不亂點"),
], y=top, size=11, gap=4)
formula(s, [
    "目標值  v* = clamp( v_min + f·(v_max − v_min) , v_min , v_max )",
    "軌道位置 x(v) = L + inset + ((v − v_min)/(v_max − v_min))·(W − 2·inset)",
    "達標判定 |v_觀測 − v*| ≤ max(1, 0.04·(v_max − v_min))",
], y=3.55, h=1.15, size=11)
panel(s, 0.62, 4.95, 12.09, 1.6, fill=RGBColor(0xFF, 0xFF, 0xFF), border=RGBColor(0xA6, 0xA6, 0xA6))
tf = _tb(s, 0.86, 5.09, 11.6, 0.3)
_para(tf, "四道安全檢查（每一道都對應一次真機失敗）", size=10.5, color=ACCENT2, bold=True,
      first=True, after=0)
bullets(s, [
    ("B", "量程為 0 → 拒絕（載入中的進度條曾被誤判為「已達標」，導致假成功）"),
    ("B", "候選是唯讀元件 → 拒絕　｜　沒有實體節點 → 只能點，不算完成"),
], x=0.86, y=5.45, w=11.6, h=1.0, size=9.5, gap=3)

# ══ 16 安全 ═══════════════════════════════════════════════════════════
s, top = page("安全自動機：把「不能做」寫成規則", "不是靠提示詞，而是靠可驗證、可審計的判定")
rows = [
    ["風險類型", "觸發條件（節錄）", "判定"],
    ["付款／轉帳", "可見文字或目標含付款、金額、轉帳語彙", "需人工確認"],
    ["刪除／清除", "目標或頁面語意為刪除、清除、格式化", "需人工確認"],
    ["帳號／驗證碼", "出現驗證碼、一次性密碼、密碼欄位", "整段任務中止"],
    ["離開目標 App", "動作後套件改變，且不是同一可見介面", "記錄並重新確認"],
    ["內容列誤判", "目標位於列表項（信件主旨可能剛好含「驗證碼」）", "豁免，不觸發"],
]
table(s, rows, y=top, widths=[2.3, 6.4, 3.4], size=9.8, row_h=0.5, first_col_bold=True)
bullets(s, [
    ("H", "兩個容易被忽略的設計"),
    ("B", "英文關鍵詞必須是完整單詞：`pay` 不能命中 `play`（曾誤攔播放鍵）"),
    ("B", "內容列豁免：信件主旨常含「驗證碼」，那是內容不是風控關卡"),
    ("B", "所有判定都寫入安全日誌（閘門、動作、目標、套件），可事後審計"),
], y=4.55, size=10.5, gap=4)

# ══ 17 動作空間與契約 ═════════════════════════════════════════════════
s, top = page("動作空間與動作契約", "三步就能描述任何操作；每個動作都能證明自己生效")
rows = [
    ["原語", "細分碼", "意義", "儲存成本"],
    ["LAUNCH", "APP / LINK / NOTIF", "啟動 App、深鏈直達、開啟通知", "5–7 bytes"],
    ["INTERACT", "TAP / SDN / SUP / SLR / LNG / BAK / WAI / SUB / VAL", "點、滾動、橫滑、長按、返回、等待、送出、設值", "5 bytes"],
    ["INPUT", "TXT / CLR / RPL", "輸入、清除、整段替換（值由參數提供）", "8–14 bytes"],
]
table(s, rows, y=top, widths=[1.5, 5.6, 3.9, 1.4], size=9.5, row_h=0.55, first_col_bold=True)
bullets(s, [
    ("H", "為什麼是三類（完備性）"),
    ("B", "任何操作必然落在：開啟某個東西／與畫面互動／輸入文字；三者互斥且窮盡"),
    ("B", "細分碼只影響「怎麼做」，不影響「做什麼」，策略層只需理解三類"),
], y=3.55, size=10.3, gap=3)
formula(s, [
    "意圖鍵 = 雜湊( 套件 ‖ 動作 ‖ 選擇器 ‖ 頁面姿態 ‖ 頁面簽名 ‖ 參數槽 )",
    "同一意圖鍵在 2.5 秒內重複出現 → 視為重複動作直接略過（冪等）",
    "成效三態：已生效 / 無效果 / 非預期效果（非預期立即回退並記錄）",
], y=4.72, h=1.1, size=10)
tf = _tb(s, 0.62, 6.0, 12.09, 0.8)
_para(tf, "選擇器優先序：識別碼 → 角色＋文字 → 座標。座標最後，因為它最容易因版面微調而失效。",
      size=9.8, color=MUTED, first=True, after=0, spacing=1.25)

# ══ 18 幾何與時序 ═════════════════════════════════════════════════════
s, top = page("三組關鍵公式：滾動、等待、姿態", "把「手感」變成可計算、可重複的數字")
formula(s, [
    "滾動距離  d = clamp( |y_目標 − y_中心| + 餘裕 , 0.125·H , 0.50·H )　單次上限 0.80·H",
    "           見底 ⇔ 頁面尾部簽名不變 ∧ 底部節點位移 < 5px ∧ 無新滾動事件",
    "",
    "等待上限  wait = clamp( P95(響應延遲) × 1.15 , 300ms , 2000ms )",
    "           響應延遲 = 派發時刻 → 第一次內容變化事件（不是整個動作耗時）",
    "",
    "姿態推論  z = W·h + b（h 為 12 維特徵，W 為 12×9）　p = softmax(z)；信心 < 0.45 回退啟發式",
], x=0.62, y=top, w=8.4, h=2.6, size=9.8)
bullets(s, [
    ("H", "12 維特徵"),
    ("B", "節點數、可點比例、可編輯數"),
    ("B", "可滾動數、清單容器、卡片數"),
    ("B", "是否有工具列／底部導覽"),
    ("B", "是否有輸入框／彈窗"),
    ("B", "動態分數、捲動位置"),
    ("H", "9 種姿態"),
    ("B", "首頁、資訊流、詳情、搜尋結果、表單、彈窗、媒體、結帳、空狀態"),
], x=9.3, y=top, w=3.4, h=3.2, size=9, gap=3)
tf = _tb(s, 0.62, 4.45, 12.09, 1.5)
_para(tf, "為什麼不固定 sleep：固定等待是最慢與最不穩定的組合——快的頁面浪費時間，"
          "慢的頁面照樣失敗。以「第一次內容變化」量測真實響應，再取該控件的 95 百分位，"
          "既能跟上快頁面，也能容忍慢頁面。",
      size=10.5, color=BODY, first=True, after=0, spacing=1.3)

# ══ 19 世界模型與規劃 ═════════════════════════════════════════════════
s, top = page("世界模型與規劃：記住、重放、必要時先想一想",
              "用「前置條件 + 動作」記錄，而不是記錄座標")
formula(s, [
    "重放命中 ⇔ J(簽名_當初, 簽名_現在) ≥ 0.40",
    "            ∧ 動作相同 ∧ 目標相似度 ≥ 0.60",
    "",
    "J(A, B) = |A ∩ B| / |A ∪ B|　　命中後仍以事後條件驗證，不信任歷史",
], x=0.62, y=top, w=6.2, h=1.75, size=10.5)
formula(s, [
    "UCT(s, a) = Q(s, a) + c·√( ln N(s) / N(s, a) )",
    "c ≈ 1.41（√2）　｜　rollouts = 120（行動裝置延遲上限）",
], x=6.75, y=top, w=5.95, h=1.75, size=10.5)
note(s, "註：J 為 Jaccard 相似度（交集／聯集）；Q 為平均回報、N 為造訪次數、c 為探索常數。",
     y=top + 1.8, x=6.75, w=5.95)
bullets(s, [
    ("H", "為什麼用「動作前」而不是「動作後」"),
    ("B", "實測：動作前簽名相似度可達 1.0，動作後只有 0.18——因為頁面變化太大"),
    ("B", "能不能重放，取決於「現在是不是當初的狀態」"),
], y=3.5, size=10.5, gap=4)
bullets(s, [
    ("H", "MCTS 的三道保險（皆為實戰補上）"),
    ("B", "預期成功率 < 0.25 不執行"),
    ("B", "定位到的候選必須有自身語意證據"),
    ("B", "候選必須有穩定識別碼，否則不執行"),
], y=4.55, size=10.3, gap=3)
formula(s, [
    "快路徑：同 App + 同動作 + 曾成功 + 前置條件命中 → 跳過文字向量（省 60–500ms）",
], y=6.02, h=0.6, size=10)

# ══ 20 評測與失敗處理 ═════════════════════════════════════════════════
s, top = page("評測與失敗處理",
              "「步」＝ 一次成功的介面動作（點擊／輸入／捲動／返回），不含等待與重試")
rows = [
    ["難度", "題數", "最低必要步數", "典型目標數", "必要能力", "逾時上限", "通過率目標"],
    ["EASY", "30", "1–2（中位 2）", "1", "單一動作：啟動 App 或一次搜尋", "40–50s", "≥ 95%"],
    ["MEDIUM", "30", "4–5（中位 4）", "2", "搜尋 → 選取 → 返回；兩個頁面內完成", "70–90s", "≥ 80%"],
    ["HARD", "25", "10–20（中位 13）", "3–4", "多層導覽；需要捲動或條件篩選", "150–240s", "≥ 60%"],
    ["EXTREME", "15", "32–35（中位 33）", "≥ 6", "分支迴圈：多次搜尋／開啟／返回，且需容錯", "360–400s", "≥ 30%"],
]
table(s, rows, y=top, widths=[1.15, 0.75, 2.0, 1.15, 4.6, 1.25, 1.35], size=9.5,
      row_h=0.44, first_col_bold=True)
nformula = top + 0.44 * 5 + 0.16
formula(s, [
    "分級原則：主軸 = 必須成功的最少動作數；次軸 = 是否跨頁、是否跨 App、是否需要容錯重試",
], y=nformula, h=0.52, size=9.8)
by = nformula + 0.75
bullets(s, [
    ("H", "評測怎麼跑（避免自我欺騙）"),
    ("B", "判定用內部訊號（任務序號＋結果），不看畫面文字"),
    ("B", "每題前先強制停止目標 App，確保從冷啟動首頁開始"),
    ("B", "App 未安裝或無此功能 → 記為跳過，不計入通過率"),
    ("B", "實測：EASY 90%（9/10）｜MEDIUM 60%（6/10）｜HARD、EXTREME 待完整跑"),
], x=0.62, y=by, w=6.0, h=2.2, size=9.8, gap=3)
bullets(s, [
    ("H", "失敗時的行為"),
    ("B", "三級恢復：去彈窗(1) → 滾動尋路(2) → 微教學(3)；達上限即全域熔斷"),
    ("B", "部分完成：同一 App 的連續目標為一段，同段失敗就停該段，其餘照做"),
    ("B", "三種回報：全部完成／部分完成（列出未完成原因）／無法完成"),
], x=6.9, y=by, w=5.8, h=2.2, size=9.8, gap=3)
tf = _tb(s, 0.62, 6.5, 12.09, 0.42)
_para(tf, "已知邊界：自繪元件若不回報數值範圍，數值調整只能誠實失敗；選單展開／通知／縮放手勢尚未實作。",
      size=9.2, color=MUTED, first=True, after=0)

out = os.path.join(os.getcwd(), "WAE_V26_Architecture_Whitepaper.pptx")
prs.save(out)
print("saved:", out, "slides:", len(prs.slides._sldIdLst))
