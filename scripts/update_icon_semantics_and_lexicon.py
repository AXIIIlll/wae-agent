import os

ASSET_DIR = r"e:\NewAndroidApp\app\src\main\assets\open_source_ai_databases"

# P1 (Item 6): Fix mislabels & tag 143+ gesture, bottom-sheet, arrow, expand/collapse, carousel, and toggle/checkbox icons
ICON_ROLE_UPDATES = {
    # 1. SWIPE_UP_EXPAND_SHEET (上滑展開底部抽屜 / 框頂扁平橢圓把手 / 向上展開箭頭 / 回到頂部)
    "bottom_sheets": ("SWIPE_UP_EXPAND_SHEET", "上滑展開抽屜|底部抽屜|框頂扁平橢圓|拉開面板|展開詳情|抽屜把手", "上滑展开抽屉|底部抽屉|框顶扁平椭圆|拉开面板|展开详情|抽屉把手"),
    "drag_handle": ("SWIPE_UP_EXPAND_SHEET", "上滑展開抽屜|抽屜拖曳把手|扁平橢圓橫條|拉開面板|展開詳情|拖曳橫桿", "上滑展开抽屉|抽屉拖拽把手|扁平椭圆横条|拉开面板|展开详情|拖拽横杆"),
    "bottom_panel_open": ("SWIPE_UP_EXPAND_SHEET", "展開底部面板|上滑展開抽屜|底部抽屜開啟|拉開詳情", "展开底部面板|上滑展开抽屉|底部抽屉开启|拉开详情"),
    "bottom_drawer": ("SWIPE_UP_EXPAND_SHEET", "底部抽屜|上滑展開抽屜|拉開抽屜", "底部抽屉|上滑展开抽屉|拉开抽屉"),
    "horizontal_rule": ("SWIPE_UP_EXPAND_SHEET", "扁平橫條把手|上滑展開抽屜|水平分隔線|頂部拖曳條", "扁平横条把手|上滑展开抽屉|水平分隔线|顶部拖拽条"),
    "minimize": ("SWIPE_UP_EXPAND_SHEET", "扁平短橫條|最小化|底部抽屜把手|上滑展開", "扁平短横条|最小化|底部抽屉把手|上滑展开"),
    "drag_indicator": ("SWIPE_UP_EXPAND_SHEET", "拖曳把手|拖拉排序|上滑展開", "拖拽把手|拖拉排序|上滑展开"),
    "drag_pan": ("SWIPE_UP_EXPAND_SHEET", "拖曳移動|滑動展開|平移畫布", "拖拽移动|滑动展开|平移画布"),
    "drag_click": ("SWIPE_UP_EXPAND_SHEET", "點擊拖曳|拉開面板", "点击拖拽|拉开面板"),
    "swipe_up": ("SWIPE_UP_EXPAND_SHEET", "上滑|往上滑|向上滑動|上滑展開|上滑查看更多", "上滑|往上滑|向上滑动|上滑展开|上滑查看更多"),
    "swipe_up_alt": ("SWIPE_UP_EXPAND_SHEET", "上滑手勢|往上滑動|上滑展開", "上滑手势|往上滑动|上滑展开"),
    "swipe_vertical": ("SWIPE_UP_EXPAND_SHEET", "上下滑動|垂直滑動|展開或收合抽屜", "上下滑动|垂直滑动|展开或收合抽屉"),
    "expand_less": ("SWIPE_UP_EXPAND_SHEET", "向上展開|山字形上箭頭|上滑展開|收起或展開|回到頂部", "向上展开|山字形上箭头|上滑展开|收起或展开|回到顶部"),
    "keyboard_arrow_up": ("SWIPE_UP_EXPAND_SHEET", "向上箭頭|向上展開|上滑展開抽屜|回到頂部", "向上箭头|向上展开|上滑展开抽屉|回到顶部"),
    "keyboard_double_arrow_up": ("SWIPE_UP_EXPAND_SHEET", "雙向上箭頭|快速上滑展開|回到最頂部", "双向上箭头|快速上滑展开|回到最顶部"),
    "chevron_line_up": ("SWIPE_UP_EXPAND_SHEET", "頂部山字箭頭|展開至頂|回到頂部|上滑展開", "顶部山字箭头|展开至顶|回到顶部|上滑展开"),
    "expand_circle_up": ("SWIPE_UP_EXPAND_SHEET", "圓形向上展開|上滑展開|回到頂部", "圆形向上展开|上滑展开|回到顶部"),
    "arrow_upward": ("SWIPE_UP_EXPAND_SHEET", "向上箭頭|上滑|回到頂部|升序|發送", "向上箭头|上滑|回到顶部|升序|发送"),
    "arrow_upward_alt": ("SWIPE_UP_EXPAND_SHEET", "向上箭頭|上滑展開|往上移", "向上箭头|上滑展开|往上移"),
    "arrow_circle_up": ("SWIPE_UP_EXPAND_SHEET", "圓形上箭頭|上滑展開|回到頂部", "圆形上箭头|上滑展开|回到顶部"),
    "arrow_drop_up": ("SWIPE_UP_EXPAND_SHEET", "上指小三角|向上展開選單|升序排列", "上指小三角|向上展开菜单|升序排列"),
    "arrows_more_up": ("SWIPE_UP_EXPAND_SHEET", "雙重向上展開|載入上方更多|上滑展開", "双重向上展开|加载上方更多|上滑展开"),
    "arrow_shape_up": ("SWIPE_UP_EXPAND_SHEET", "粗體上箭頭|上滑|往上提升", "粗体上箭头|上滑|往上提升"),
    "arrow_shape_up_stack": ("SWIPE_UP_EXPAND_SHEET", "堆疊上箭頭|連續上滑|展開更多", "堆叠上箭头|连续上滑|展开更多"),
    "arrow_shape_up_stack_2": ("SWIPE_UP_EXPAND_SHEET", "雙層上箭頭|快速上滑展開", "双层上箭头|快速上滑展开"),
    "arrow_warm_up": ("SWIPE_UP_EXPAND_SHEET", "向上升溫箭頭|往上調高", "向上升温箭头|往上调高"),
    "move_up": ("SWIPE_UP_EXPAND_SHEET", "往上移動|上移項目|上滑", "往上移动|上移项目|上滑"),
    "vertical_align_top": ("SWIPE_UP_EXPAND_SHEET", "回到頂部|置頂對齊|移至最上方", "回到顶部|置顶对齐|移至最上方"),
    "scrollable_header": ("SWIPE_UP_EXPAND_SHEET", "可捲動頂欄|上滑收起頂欄", "可滚动顶栏|上滑收起顶栏"),
    "top_panel_open": ("SWIPE_UP_EXPAND_SHEET", "展開頂部面板|打開上方面板", "展开顶部面板|打开上方面板"),
    "text_select_move_up": ("SWIPE_UP_EXPAND_SHEET", "游標上移|向上選擇", "光标上移|向上选择"),
    "clock_arrow_up": ("SWIPE_UP_EXPAND_SHEET", "時間遞增|往上調整時間", "时间递增|往上调整时间"),
    "hourglass_arrow_up": ("SWIPE_UP_EXPAND_SHEET", "計時增加|往上調整", "计时增加|往上调整"),
    "thermostat_arrow_up": ("SWIPE_UP_EXPAND_SHEET", "調高溫度|往上調溫", "调高温度|往上调温"),
    "tilt_arrow_up": ("SWIPE_UP_EXPAND_SHEET", "向上傾斜|往上微調", "向上倾斜|往上微调"),
    "timer_arrow_up": ("SWIPE_UP_EXPAND_SHEET", "計時上調|增加時間", "计时上调|增加时间"),
    "computer_arrow_up": ("SWIPE_UP_EXPAND_SHEET", "畫面投射上移|上傳至電腦", "画面投射上移|上传至电脑"),
    "arrow_upload_progress": ("ATTACH_UPLOAD_CAMERA", "上傳進度|正在上傳", "上传进度|正在上传"),
    "arrow_upload_ready": ("ATTACH_UPLOAD_CAMERA", "準備上傳|上傳完成", "准备上传|上传完成"),

    # 2. SWIPE_DOWN_COLLAPSE_SHEET (下滑收合面板 / 下拉選單 / 向下展開清單)
    "expand_more": ("SWIPE_DOWN_COLLAPSE_SHEET", "向下展開|下拉選單|下滑收合面板|展開更多項目", "向下展开|下拉菜单|下滑收合面板|展开更多项目"),
    "keyboard_arrow_down": ("SWIPE_DOWN_COLLAPSE_SHEET", "向下箭頭|下拉選單|下滑收合抽屜|展開下方內容", "向下箭头|下拉菜单|下滑收合抽屉|展开下方内容"),
    "keyboard_double_arrow_down": ("SWIPE_DOWN_COLLAPSE_SHEET", "雙向下箭頭|快速下滑|收合面板|捲到底部", "双向下箭头|快速下滑|收合面板|滚到底部"),
    "arrow_downward": ("SWIPE_DOWN_COLLAPSE_SHEET", "向下箭頭|下滑|捲動到底部|降序", "向下箭头|下滑|滚动到底部|降序"),
    "arrow_downward_alt": ("SWIPE_DOWN_COLLAPSE_SHEET", "向下箭頭|下滑收合|往下移", "向下箭头|下滑收合|往下移"),
    "arrow_circle_down": ("SWIPE_DOWN_COLLAPSE_SHEET", "圓形下箭頭|下滑展開|捲動到底部", "圆形下箭头|下滑展开|滚动到底部"),
    "arrow_drop_down": ("SWIPE_DOWN_COLLAPSE_SHEET", "下拉小三角|展開下拉選單|選擇項目", "下拉小三角|展开下拉菜单|选择项目"),
    "arrow_drop_down_circle": ("SWIPE_DOWN_COLLAPSE_SHEET", "圓形下拉箭頭|展開下拉選單", "圆形下拉箭头|展开下拉菜单"),
    "arrows_more_down": ("SWIPE_DOWN_COLLAPSE_SHEET", "雙重向下展開|顯示下方更多|下滑查看更多", "双重向下展开|显示下方更多|下滑查看更多"),
    "expand_circle_down": ("SWIPE_DOWN_COLLAPSE_SHEET", "圓形向下展開|展開更多詳情|下拉收合", "圆形向下展开|展开更多详情|下拉收合"),
    "swipe_down": ("SWIPE_DOWN_COLLAPSE_SHEET", "下滑|往下滑|向下滑動|下滑收合面板|下拉重新整理", "下滑|往下滑|向下滑动|下滑收合面板|下拉刷新"),
    "swipe_down_alt": ("SWIPE_DOWN_COLLAPSE_SHEET", "下滑手勢|往下滑動|下滑收合", "下滑手势|往下滑动|下滑收合"),
    "bottom_panel_close": ("SWIPE_DOWN_COLLAPSE_SHEET", "收合底部面板|關閉底部抽屜|下滑收起", "收合底部面板|关闭底部抽屉|下滑收起"),
    "collapse_all": ("SWIPE_DOWN_COLLAPSE_SHEET", "全部收合|全部摺疊|收起所有清單", "全部收合|全部折叠|收起所有列表"),
    "collapse_content": ("SWIPE_DOWN_COLLAPSE_SHEET", "收合內容|縮小面板|收起詳情", "收合内容|缩小面板|收起详情"),
    "unfold_less": ("SWIPE_DOWN_COLLAPSE_SHEET", "向內收合|摺疊隱藏內容|收起清單", "向内收合|折叠隐藏内容|收起列表"),
    "unfold_less_double": ("SWIPE_DOWN_COLLAPSE_SHEET", "雙重向內收合|完全摺疊面板", "双重向内收合|完全折叠面板"),
    "move_down": ("SWIPE_DOWN_COLLAPSE_SHEET", "往下移動|下移項目|下滑", "往下移动|下移项目|下滑"),
    "vertical_align_bottom": ("SWIPE_DOWN_COLLAPSE_SHEET", "移至底部|置底對齊|捲到最下方", "移至底部|置底对齐|滚到最下方"),
    "south": ("SWIPE_DOWN_COLLAPSE_SHEET", "向南箭頭|向下箭頭|往下滑", "向南箭头|向下箭头|往下滑"),
    "arrow_cool_down": ("SWIPE_DOWN_COLLAPSE_SHEET", "向下降溫箭頭|往下調低", "向下降温箭头|往下调低"),
    "text_select_move_down": ("SWIPE_DOWN_COLLAPSE_SHEET", "游標下移|向下選擇", "光标下移|向下选择"),
    "clock_arrow_down": ("SWIPE_DOWN_COLLAPSE_SHEET", "時間遞減|往下調整時間", "时间递减|往下调整时间"),
    "hourglass_arrow_down": ("SWIPE_DOWN_COLLAPSE_SHEET", "計時減少|往下調整", "计时减少|往下调整"),
    "thermostat_arrow_down": ("SWIPE_DOWN_COLLAPSE_SHEET", "調低溫度|往下調溫", "调低温度|往下调温"),
    "tilt_arrow_down": ("SWIPE_DOWN_COLLAPSE_SHEET", "向下傾斜|往下微調", "向下倾斜|往下微调"),
    "timer_arrow_down": ("SWIPE_DOWN_COLLAPSE_SHEET", "計時下調|減少時間", "计时下调|减少时间"),
    "mobile_arrow_down": ("SWIPE_DOWN_COLLAPSE_SHEET", "下載至手機|手機下滑", "下载至手机|手机下滑"),
    "watch_arrow_down": ("SWIPE_DOWN_COLLAPSE_SHEET", "手錶下滑|同步至手錶", "手表下滑|同步至手表"),

    # 3. EXPAND_FULL_CONTENT (雙向展開全文 / 展開隱藏區塊 / 全螢幕放大)
    "unfold_more": ("EXPAND_FULL_CONTENT", "上下雙向展開|展開更多內容|展開詳情|攤開清單", "上下双向展开|展开更多内容|展开详情|摊开列表"),
    "unfold_more_double": ("EXPAND_FULL_CONTENT", "雙重上下展開|展開全部隱藏內容", "双重上下展开|展开全部隐藏内容"),
    "expand_content": ("EXPAND_FULL_CONTENT", "展開完整內容|四角放大展開|展開詳情", "展开完整内容|四角放大展开|展开详情"),
    "expand_all": ("EXPAND_FULL_CONTENT", "全部展開|攤開所有項目|展開全文", "全部展开|摊开所有项目|展开全文"),
    "expansion_panels": ("EXPAND_FULL_CONTENT", "摺疊展開面板|手風琴面板|展開詳情", "折叠展开面板|手风琴面板|展开详情"),
    "open_in_full": ("EXPAND_FULL_CONTENT", "對角展開全螢幕|放大展開|完整檢視", "对角展开全屏|放大展开|完整查看"),
    "expand": ("EXPAND_FULL_CONTENT", "上下撐開|展開區塊|放大高度", "上下撑开|展开区块|放大高度"),
    "fullscreen": ("EXPAND_FULL_CONTENT", "全螢幕|放大全螢幕|完整畫面", "全屏|放大全屏|完整画面"),
    "maximize": ("EXPAND_FULL_CONTENT", "最大化視窗|頂部橫條展開", "最大化窗口|顶部横条展开"),
    "arrows_outward": ("EXPAND_FULL_CONTENT", "四向往外擴展|放大展開", "四向往外扩展|放大展开"),
    "arrows_up_down_circle": ("EXPAND_FULL_CONTENT", "上下展開圓鈕|垂直調整高度", "上下展开圆钮|垂直调整高度"),
    "arrows_input": ("SWIPE_DOWN_COLLAPSE_SHEET", "向內集中收合|縮小範圍", "向内集中收合|缩小范围"),
    "arrows_output": ("EXPAND_FULL_CONTENT", "向外擴展展開|放大範圍", "向外扩展展开|放大范围"),
    "fit_page": ("EXPAND_FULL_CONTENT", "符合頁面大小|自動縮放展開", "适应页面大小|自动缩放展开"),
    "fit_page_height": ("EXPAND_FULL_CONTENT", "符合頁面高度|垂直展開", "适应页面高度|垂直展开"),
    "fit_page_width": ("EXPAND_FULL_CONTENT", "符合頁面寬度|水平展開", "适应页面宽度|水平展开"),
    "pageless": ("EXPAND_FULL_CONTENT", "無分頁連續展開|展開長文", "无分页连续展开|展开长文"),
    "vertical_align_center": ("EXPAND_FULL_CONTENT", "垂直置中對齊|中間收合對齊", "垂直居中对齐|中间收合对齐"),

    # 4. SWIPE_HORIZONTAL_CAROUSEL (左右滑動翻頁 / 橫向膠囊列捲動 / 下一頁與上一頁)
    "swipe_left": ("SWIPE_HORIZONTAL_CAROUSEL", "左滑|往左滑|向左滑動|下一張卡片|橫向滑動", "左滑|往左滑|向左滑动|下一张卡片|横向滑动"),
    "swipe_left_2": ("SWIPE_HORIZONTAL_CAROUSEL", "雙指左滑|向左滑動翻頁", "双指左滑|向左滑动翻页"),
    "swipe_left_alt": ("SWIPE_HORIZONTAL_CAROUSEL", "左滑手勢|往左撥動", "左滑手势|往左拨动"),
    "swipe_right": ("SWIPE_HORIZONTAL_CAROUSEL", "右滑|往右滑|向右滑動|上一張卡片|橫向滑動", "右滑|往右滑|向右滑动|上一张卡片|横向滑动"),
    "swipe_right_2": ("SWIPE_HORIZONTAL_CAROUSEL", "雙指右滑|向右滑動翻頁", "双指右滑|向右滑动翻页"),
    "swipe_right_alt": ("SWIPE_HORIZONTAL_CAROUSEL", "右滑手勢|往右撥動", "右滑手势|往右拨动"),
    "swipe": ("SWIPE_HORIZONTAL_CAROUSEL", "左右滑動手勢|滑動翻頁|切換卡片", "左右滑动手势|滑动翻页|切换卡片"),
    "chevron_left": ("SWIPE_HORIZONTAL_CAROUSEL", "左尖角箭頭|上一頁|往左翻|返回", "左尖角箭头|上一页|往左翻|返回"),
    "chevron_right": ("SWIPE_HORIZONTAL_CAROUSEL", "右尖角箭頭|下一頁|進入子項|展開詳情|往右翻", "右尖角箭头|下一页|进入子项|展开详情|往右翻"),
    "chevron_backward": ("SWIPE_HORIZONTAL_CAROUSEL", "向左折線箭頭|上一頁|後退翻頁", "向左折线箭头|上一页|后退翻页"),
    "chevron_forward": ("SWIPE_HORIZONTAL_CAROUSEL", "向右折線箭頭|下一頁|進入詳情|前進翻頁", "向右折线箭头|下一页|进入详情|前进翻页"),
    "keyboard_arrow_left": ("SWIPE_HORIZONTAL_CAROUSEL", "向左箭頭|上一頁|往左切換", "向左箭头|上一页|往左切换"),
    "keyboard_arrow_right": ("SWIPE_HORIZONTAL_CAROUSEL", "向右箭頭|下一頁|進入選單項|往右切換", "向右箭头|下一页|进入菜单项|往右切换"),
    "keyboard_double_arrow_left": ("SWIPE_HORIZONTAL_CAROUSEL", "雙向左箭頭|回到第一頁|快速左移", "双向左箭头|回到第一页|快速左移"),
    "keyboard_double_arrow_right": ("SWIPE_HORIZONTAL_CAROUSEL", "雙向右箭頭|跳到最後一頁|快速右移", "双向右箭头|跳到最后一页|快速右移"),
    "arrow_left": ("SWIPE_HORIZONTAL_CAROUSEL", "左三角箭頭|往左切換|上一個", "左三角箭头|往左切换|上一个"),
    "arrow_left_alt": ("SWIPE_HORIZONTAL_CAROUSEL", "向左長箭頭|返回上一項|左移", "向左长箭头|返回上一项|左移"),
    "arrow_right": ("SWIPE_HORIZONTAL_CAROUSEL", "右三角箭頭|往右切換|下一個|展開子選單", "右三角箭头|往右切换|下一个|展开子菜单"),
    "arrow_right_alt": ("SWIPE_HORIZONTAL_CAROUSEL", "向右長箭頭|前往下一項|繼續", "向右长箭头|前往下一项|继续"),
    "arrow_circle_left": ("SWIPE_HORIZONTAL_CAROUSEL", "圓形左箭頭|上一頁|往左翻頁", "圆形左箭头|上一页|往左翻页"),
    "arrow_circle_right": ("SWIPE_HORIZONTAL_CAROUSEL", "圓形右箭頭|下一頁|前往下一步", "圆形右箭头|下一页|前往下一步"),
    "arrow_forward": ("SWIPE_HORIZONTAL_CAROUSEL", "向前右箭頭|下一步|前往|下一頁", "向前右箭头|下一步|前往|下一页"),
    "arrow_forward_ios": ("SWIPE_HORIZONTAL_CAROUSEL", "右側尖角箭頭|進入設定子頁|下一頁", "右侧尖角箭头|进入设置子页|下一页"),
    "arrows_left_right_circle": ("SWIPE_HORIZONTAL_CAROUSEL", "左右雙向滑動|水平切換", "左右双向滑动|水平切换"),
    "expand_circle_right": ("SWIPE_HORIZONTAL_CAROUSEL", "圓形向右展開|進入詳情|下一頁", "圆形向右展开|进入详情|下一页"),
    "double_arrow": ("SWIPE_HORIZONTAL_CAROUSEL", "雙右箭頭|快速前進|下一階段", "双右箭头|快速前进|下一阶段"),
    "single_arrow": ("SWIPE_HORIZONTAL_CAROUSEL", "單右箭頭|前進下一步", "单右箭头|前进下一步"),
    "east": ("SWIPE_HORIZONTAL_CAROUSEL", "向東右箭頭|往右滑|下一步", "向东右箭头|往右滑|下一步"),
    "view_carousel": ("SWIPE_HORIZONTAL_CAROUSEL", "水平輪播圖卡|左右滑動卡片", "水平轮播图卡|左右滑动卡片"),
    "page_control": ("SWIPE_HORIZONTAL_CAROUSEL", "分頁圓點指示器|左右翻頁切換", "分页圆点指示器|左右翻页切换"),
    "two_pager": ("SWIPE_HORIZONTAL_CAROUSEL", "雙頁左右檢視|左右翻頁", "双页左右查看|左右翻页"),
    "two_pager_store": ("SWIPE_HORIZONTAL_CAROUSEL", "商店雙頁目錄|左右翻頁瀏覽", "商店双页目录|左右翻页浏览"),
    "first_page": ("SWIPE_HORIZONTAL_CAROUSEL", "第一頁|跳至首頁", "第一页|跳至首页"),
    "last_page": ("SWIPE_HORIZONTAL_CAROUSEL", "最後一頁|跳至末頁", "最后一页|跳至末页"),
    "left_panel_open": ("SWIPE_HORIZONTAL_CAROUSEL", "展開左側面板|拉出側邊欄", "展开左侧面板|拉出侧边栏"),
    "right_panel_open": ("SWIPE_HORIZONTAL_CAROUSEL", "展開右側面板|拉出右欄", "展开右侧面板|拉出右栏"),
    "right_panel_close": ("CLOSE_MODAL", "關閉右側面板|收起右欄", "关闭右侧面板|收起右栏"),
    "switch_left": ("SWIPE_HORIZONTAL_CAROUSEL", "切換至左側|往左切換", "切换至左侧|往左切换"),
    "switch_right": ("SWIPE_HORIZONTAL_CAROUSEL", "切換至右側|往右切換", "切换至右侧|往右切换"),
    "compare_arrows": ("SWIPE_HORIZONTAL_CAROUSEL", "左右交換箭頭|起訖點互換|切換方向", "左右交换箭头|起讫点互换|切换方向"),
    "arrow_range": ("SWIPE_HORIZONTAL_CAROUSEL", "左右範圍雙箭頭|水平調整寬度", "左右范围双箭头|水平调整宽度"),
    "subdirectory_arrow_left": ("SWIPE_HORIZONTAL_CAROUSEL", "轉折向左箭頭|返回上層目錄", "转折向左箭头|返回上层目录"),
    "subdirectory_arrow_right": ("SWIPE_HORIZONTAL_CAROUSEL", "轉折向右箭頭|進入子目錄|回覆", "转折向右箭头|进入子目录|回复"),
    "mobile_arrow_right": ("SWIPE_HORIZONTAL_CAROUSEL", "手機右移|傳送至外部", "手机右移|传送至外部"),

    # 5. TOGGLE_SWITCH_CHECKBOX (開關切換膠囊 / 核取勾選框 / 單選圓鈕)
    "toggle_on": ("TOGGLE_SWITCH_CHECKBOX", "開關已開啟|切換開關|啟用狀態|膠囊開關", "开关已开启|切换开关|启用状态|胶囊开关"),
    "toggle_off": ("TOGGLE_SWITCH_CHECKBOX", "開關已關閉|切換開關|停用狀態|膠囊開關", "开关已关闭|切换开关|停用状态|胶囊开关"),
    "switch": ("TOGGLE_SWITCH_CHECKBOX", "切換開關|電源開關", "切换开关|电源开关"),
    "switches": ("TOGGLE_SWITCH_CHECKBOX", "多組切換開關|控制開關組", "多组切换开关|控制开关组"),
    "switch_off": ("TOGGLE_SWITCH_CHECKBOX", "關閉切換開關|停用開關", "关闭切换开关|停用开关"),
    "check_box": ("TOGGLE_SWITCH_CHECKBOX", "已勾選方框|核取方塊|打勾同意|勾選項目", "已勾选方框|复选框|打勾同意|勾选项目"),
    "check_box_outline_blank": ("TOGGLE_SWITCH_CHECKBOX", "未勾選空心方框|核取方塊|點擊勾選|同意條款方框", "未勾选空心方框|复选框|点击勾选|同意条款方框"),
    "indeterminate_check_box": ("TOGGLE_SWITCH_CHECKBOX", "部分勾選方框|減號核取方塊", "部分勾选方框|减号复选框"),
    "select_check_box": ("TOGGLE_SWITCH_CHECKBOX", "批次勾選方框|選擇多項", "批量勾选方框|选择多项"),
    "radio_button_checked": ("TOGGLE_SWITCH_CHECKBOX", "已選取單選圓鈕|單選框選中", "已选取单选圆钮|单选框选中"),
    "radio_button_unchecked": ("TOGGLE_SWITCH_CHECKBOX", "未選取單選圓鈕|空心單選框", "未选取单选圆钮|空心单选框"),
    "radio_button_partial": ("TOGGLE_SWITCH_CHECKBOX", "部分選取單選鈕", "部分选取单选钮"),
    "legend_toggle": ("TOGGLE_SWITCH_CHECKBOX", "圖例顯示開關|切換圖例", "图例显示开关|切换图例"),
    "shield_toggle": ("TOGGLE_SWITCH_CHECKBOX", "安全防護開關|切換防護", "安全防护开关|切换防护"),
    "history_toggle_off": ("TOGGLE_SWITCH_CHECKBOX", "暫停歷史紀錄開關", "暂停历史记录开关"),
    "switch_access": ("TOGGLE_SWITCH_CHECKBOX", "開關控制無障礙", "开关控制无障碍"),
    "switch_access_2": ("TOGGLE_SWITCH_CHECKBOX", "開關控制輔助", "开关控制辅助"),
    "switch_access_3": ("TOGGLE_SWITCH_CHECKBOX", "開關控制選項", "开关控制选项"),
    "switch_access_shortcut": ("TOGGLE_SWITCH_CHECKBOX", "開關控制捷徑", "开关控制快捷方式"),

    # Additional mislabel fixes found during full scan
    "arrow_menu_open": ("MORE_OVERFLOW_MENU", "展開選單|打開側邊選單", "展开菜单|打开侧边菜单"),
    "arrow_outward": ("OPEN_EXTERNAL_WEBSITE", "右上斜箭頭|開啟外部網站|跳轉連結", "右上斜箭头|开启外部网站|跳转链接"),
    "arrow_top_right": ("OPEN_EXTERNAL_WEBSITE", "右上箭頭|開啟外部連結", "右上箭头|开启外部链接"),
    "mobile_arrow_up_right": ("OPEN_EXTERNAL_WEBSITE", "手機外部跳轉|開啟連結", "手机外部跳转|开启链接"),
    "arrow_top_left": ("BACK_UP_NAVIGATION", "左上箭頭|填入搜尋詞|返回上層", "左上箭头|填入搜索词|返回上层"),
    "north_west": ("BACK_UP_NAVIGATION", "左上斜箭頭|搜尋建議填入輸入框", "左上斜箭头|搜索建议填入输入框"),
    "south_west": ("SWIPE_DOWN_COLLAPSE_SHEET", "左下斜箭頭|收合縮小", "左下斜箭头|收合缩小"),
    "south_east": ("EXPAND_FULL_CONTENT", "右下斜箭頭|拉大展開", "右下斜箭头|拉大展开"),
}

def update_material_icons_tsv():
    path = os.path.join(ASSET_DIR, "google_material_icons_full_semantics.tsv")
    with open(path, "r", encoding="utf-8") as f:
        lines = f.read().splitlines()

    updated_count = 0
    new_lines = []
    for line in lines:
        if not line or line.startswith("#"):
            new_lines.append(line)
            continue
        parts = line.split("\t")
        if len(parts) < 6:
            new_lines.append(line)
            continue
        icon_name = parts[0].strip()
        if icon_name in ICON_ROLE_UPDATES:
            new_role, add_tw, add_cn = ICON_ROLE_UPDATES[icon_name]
            old_tw = [t for t in parts[3].split("|") if not t.startswith("介面圖標") and not t.startswith("地圖與地點")]
            old_cn = [t for t in parts[4].split("|") if not t.startswith("界面图标") and not t.startswith("地图与地点")]
            merged_tw = list(dict.fromkeys(add_tw.split("|") + old_tw))
            merged_cn = list(dict.fromkeys(add_cn.split("|") + old_cn))
            parts[2] = new_role
            parts[3] = "|".join(merged_tw)
            parts[4] = "|".join(merged_cn)
            updated_count += 1
        new_lines.append("\t".join(parts))

    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(new_lines) + "\n")
    print(f"Updated {updated_count} icons in google_material_icons_full_semantics.tsv")

def update_mutex_ontology_tsv():
    path = os.path.join(ASSET_DIR, "cilin_hownet_antonym_mutual_exclusion_100k.tsv")
    with open(path, "r", encoding="utf-8") as f:
        lines = f.read().splitlines()

    new_ontologies = [
        "UI_ONTOLOGY\tMa21A01=\tSWIPE_UP_EXPAND_SHEET\t上滑展開|展開抽屜|上滑展開抽屜|底部抽屜|展開詳情|拉開面板|往上拉|上拉展開|抽屜把手|框頂扁平橢圓|回到頂部|展開底部面板|上滑查看|上滑更多|展開清單|上滑展开|展开抽屉|底部抽屉|展开详情|拉开面板|抽屉把手|回到顶部|expand_sheet|bottom_sheet|bottom_sheets|drag_handle|swipe_up|expand_less|keyboard_arrow_up",
        "UI_ONTOLOGY\tMa21B01=\tSWIPE_DOWN_COLLAPSE_SHEET\t下滑收合|收合面板|收起抽屜|摺疊面板|下拉選單|展開下拉|往下拉|收起詳情|最小化|下滑收起|收起面板|折叠面板|下拉菜单|collapse_sheet|expand_more|swipe_down|keyboard_arrow_down|arrow_drop_down|dropdown",
        "UI_ONTOLOGY\tMa22A01=\tEXPAND_FULL_CONTENT\t展開全文|展開完整內容|展開全部|全螢幕|最大化|展開更多|攤開|完整檢視|展开全文|展开完整内容|展开全部|全屏|最大化|unfold_more|expand_content|open_in_full|fullscreen|expand_all",
        "UI_ONTOLOGY\tMa23A01=\tSWIPE_HORIZONTAL_CAROUSEL\t左右滑動|下一頁|上一頁|向右翻頁|向左翻頁|橫向滑動|輪播切換|下一張|上一張|左右滑动|下一页|上一页|横向滑动|轮播切换|chevron_right|chevron_left|swipe_left|swipe_right|arrow_forward_ios",
        "UI_ONTOLOGY\tMa24A01=\tTOGGLE_SWITCH_CHECKBOX\t開關|切換開關|勾選框|核取方塊|勾選|取消勾選|打勾|同意條款方框|單選鈕|开关|切换开关|勾选框|复选框|勾选|打勾|toggle_on|toggle_off|check_box|check_box_outline_blank|switch"
    ]

    existing_roles = set()
    insert_pos = 0
    for idx, line in enumerate(lines):
        if line.startswith("UI_ONTOLOGY\t"):
            parts = line.split("\t")
            if len(parts) >= 3:
                existing_roles.add(parts[2])
            insert_pos = idx + 1

    to_add = []
    for entry in new_ontologies:
        role = entry.split("\t")[2]
        if role not in existing_roles:
            to_add.append(entry)

    if to_add:
        lines[insert_pos:insert_pos] = to_add
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
        print(f"Added {len(to_add)} new UI_ONTOLOGY roles to cilin_hownet_antonym_mutual_exclusion_100k.tsv")

if __name__ == "__main__":
    update_material_icons_tsv()
    update_mutex_ontology_tsv()
