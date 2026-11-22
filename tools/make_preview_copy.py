# -*- coding: utf-8 -*-
"""產生「預覽用」副本：整體縮小 0.8 倍。

原因：本機的 LibreOffice Kit 渲染器會把投影片內容縮放 1.25 倍並裁掉右下角，
導致預覽看起來像「文字超出邊界」。把整份簡報（含字級、表格列寬）等比縮小 0.8，
渲染時剛好抵銷那 1.25 倍的錯誤，就能看到真實版面。
交付檔案仍是原始尺寸，不受影響。
"""
import sys
from pptx import Presentation
from pptx.util import Emu

SRC = sys.argv[1] if len(sys.argv) > 1 else "WAE_V26_Architecture_Whitepaper.pptx"
DST = sys.argv[2] if len(sys.argv) > 2 else "preview_copy.pptx"
K = 0.8


def scale_tf(tf):
    for p in tf.paragraphs:
        for r in p.runs:
            if r.font.size:
                r.font.size = Emu(int(r.font.size.pt * K * 12700))


def walk(shapes):
    for sh in shapes:
        if sh.shape_type == 6:  # group
            walk(sh.shapes)
            continue
        sh.left = Emu(int(sh.left * K))
        sh.top = Emu(int(sh.top * K))
        sh.width = Emu(int(sh.width * K))
        sh.height = Emu(int(sh.height * K))
        if sh.has_text_frame:
            scale_tf(sh.text_frame)
        if getattr(sh, "has_table", False) and sh.has_table:
            tbl = sh.table
            for c in tbl.columns:
                c.width = Emu(int(c.width * K))
            for row in tbl.rows:
                row.height = Emu(int(row.height * K))
                for cell in row.cells:
                    scale_tf(cell.text_frame)


prs = Presentation(SRC)
# 只縮放「內容」，保持頁面尺寸不變：
# 渲染器的 1.25 倍誤差會把內容放回原始大小，於是預覽等於真實版面。
for slide in prs.slides:
    walk(slide.shapes)
prs.save(DST)
print("saved preview copy:", DST)
