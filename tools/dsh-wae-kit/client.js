/**
 * WAE map · browser half.
 *
 * One global panel: a code map (tree + dependency mindmap), the static flow
 * overview, build/device status, the TSV catalog, the tunables editor and the
 * technical-debt board. Data comes from the Host half's `/dsh-wae-kit/api`
 * routes; nothing here writes files except the tunables editor's POST.
 */
window.__ModuleLoader__.load({
  id: 'dsh-wae-kit',
  factory(require) {
    const React = require('react')
    const h = React.createElement
    const { useCallback, useEffect, useMemo, useRef, useState } = React

    const NS = 'waeKit'
    const PANEL_ID = 'wae-map'
    const API = '/dsh-wae-kit/api'

    const zh = {
      panel: 'WAE 地圖',
      refresh: '重新掃描',
      scanning: '掃描中…',
      tabMap: '地圖',
      tabOverview: '架構總覽',
      tabBuild: '建置',
      tabData: '資料表',
      tabTunables: '調參',
      tabDebt: '技術債',
      search: '搜尋檔名、類別、套件',
      files: '檔案',
      lines: '行',
      tests: '測試',
      tracked: '追蹤',
      noSelection: '從左側選一個檔案或套件。',
      symbols: 'top-level 宣告',
      dependencies: '依賴',
      dependents: '被依賴',
      testFiles: '同套件測試',
      copyPath: '複製路徑',
      copied: '已複製路徑',
      preview: '預覽程式碼',
      close: '關閉',
      loadingPreview: '讀取中…',
      noDeps: '這個套件沒有跨套件依賴。',
      center: '目前套件',
      overviewNote: '靜態概念圖（快照），可能落後於程式碼；以右側的即時地圖為準。',
      buildStatus: '建置與裝置狀態',
      reload: '重新整理',
      runTest: '跑 full 單元測試',
      runBuild: '建置 full APK',
      install: '安裝並重綁',
      rebind: '只重綁無障礙',
      apkFull: 'full debug APK',
      apkLite: 'lite debug APK',
      unitTests: '單元測試（testFullDebugUnitTest）',
      device: '裝置與無障礙',
      noDevice: '沒有連線的裝置',
      bound: '已綁定',
      unbound: '未綁定',
      enabled: '總開關開啟',
      disabled: '總開關關閉',
      noApk: '尚未建置',
      noTests: '尚未跑測試',
      jobRunning: '執行中',
      jobDone: '完成',
      jobFailed: '失敗',
      failedTests: '失敗測試',
      datasets: 'TSV 資料表',
      datasetName: '檔案',
      rows: '資料列',
      columns: '欄位',
      size: '大小',
      bom: 'BOM',
      bomYes: '有 BOM',
      bomNo: '無',
      refs: '被引用',
      noRefs: '沒有 Kotlin 檔直接引用這個檔名',
      tunables: 'ui_tunables.tsv',
      tunablesNote: '寫回後重啟 App 生效；檔案會先備份，且不寫 BOM。',
      save: '儲存',
      saved: '已寫回，重啟 App 生效',
      debtTitle: '技術債指標',
      debtNote: '這些數字只准下降（runCatching、TODO、emoji、最大檔）。',
      runCatching: 'runCatching',
      todo: 'TODO / FIXME',
      emoji: 'emoji',
      largest: '最大的檔案',
      maxLines: '最大行數',
      empty: '沒有資料。',
    }

    const en = {
      panel: 'WAE map',
      refresh: 'Rescan',
      scanning: 'Scanning…',
      tabMap: 'Map',
      tabOverview: 'Overview',
      tabBuild: 'Build',
      tabData: 'Datasets',
      tabTunables: 'Tunables',
      tabDebt: 'Debt',
      search: 'Search files, symbols, packages',
      files: 'files',
      lines: 'lines',
      tests: 'tests',
      tracked: 'tracked',
      noSelection: 'Pick a file or package on the left.',
      symbols: 'top-level declarations',
      dependencies: 'depends on',
      dependents: 'used by',
      testFiles: 'tests in this package',
      copyPath: 'Copy path',
      copied: 'Path copied',
      preview: 'Preview code',
      close: 'Close',
      loadingPreview: 'Loading…',
      noDeps: 'No cross-package dependency for this package.',
      center: 'current package',
      overviewNote: 'Static snapshot of the concept map; it can lag behind the code. The live map is authoritative.',
      buildStatus: 'Build and device status',
      reload: 'Reload',
      runTest: 'Run full unit tests',
      runBuild: 'Build full APK',
      install: 'Install and rebind',
      rebind: 'Rebind accessibility',
      apkFull: 'full debug APK',
      apkLite: 'lite debug APK',
      unitTests: 'Unit tests (testFullDebugUnitTest)',
      device: 'Device and accessibility',
      noDevice: 'No connected device',
      bound: 'bound',
      unbound: 'not bound',
      enabled: 'master switch on',
      disabled: 'master switch off',
      noApk: 'not built yet',
      noTests: 'not run yet',
      jobRunning: 'running',
      jobDone: 'done',
      jobFailed: 'failed',
      failedTests: 'Failed tests',
      datasets: 'TSV datasets',
      datasetName: 'File',
      rows: 'Rows',
      columns: 'Columns',
      size: 'Size',
      bom: 'BOM',
      bomYes: 'BOM',
      bomNo: 'none',
      refs: 'referenced by',
      noRefs: 'No Kotlin file mentions this file name',
      tunables: 'ui_tunables.tsv',
      tunablesNote: 'Takes effect after an app restart; the file is backed up first and written without a BOM.',
      save: 'Save',
      saved: 'Written; restart the app',
      debtTitle: 'Technical debt',
      debtNote: 'These numbers may only go down (runCatching, TODO, emoji, largest file).',
      runCatching: 'runCatching',
      todo: 'TODO / FIXME',
      emoji: 'emoji',
      largest: 'Largest files',
      maxLines: 'max lines',
      empty: 'No data.',
    }

    const STYLE = `
      .wae-root { display:flex; flex-direction:column; height:100%; min-height:0; font-size:13px;
        color:var(--dsw-alias-text-primary,#1b1f24); background:var(--dsw-alias-bg-base,#fff); }
      .wae-head { display:flex; align-items:center; gap:10px; padding:10px 16px; border-bottom:1px solid var(--dsw-alias-border-subtle,rgba(15,23,42,.12)); flex:none; }
      .wae-title { font-weight:600; font-size:14px; }
      .wae-rootpath { color:var(--dsw-alias-text-secondary,#6b7280); font-size:11.5px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; flex:1; }
      .wae-btn { border:1px solid var(--dsw-alias-border-subtle,rgba(15,23,42,.12)); background:transparent; color:inherit;
        border-radius:8px; padding:4px 10px; font-size:12px; cursor:pointer; line-height:1.4; }
      .wae-btn:hover { background:var(--dsw-alias-bg-sunken,rgba(15,23,42,.04)); }
      .wae-btn[disabled] { opacity:.45; cursor:default; }
      .wae-btn.primary { border-color:transparent; background:var(--dsw-alias-accent,#2f6fed); color:#fff; }
      .wae-tabs { display:flex; gap:2px; padding:0 12px; border-bottom:1px solid var(--dsw-alias-border-subtle,rgba(15,23,42,.12)); flex:none; overflow-x:auto; }
      .wae-tab { border:0; background:transparent; color:var(--dsw-alias-text-secondary,#6b7280); padding:9px 10px; font-size:12.5px;
        cursor:pointer; border-bottom:2px solid transparent; white-space:nowrap; }
      .wae-tab.on { color:var(--dsw-alias-text-primary,#1b1f24); border-bottom-color:var(--dsw-alias-accent,#2f6fed); }
      .wae-body { flex:1; min-height:0; display:flex; }
      .wae-map { display:flex; flex:1; min-height:0; width:100%; }
      .wae-tree { width:340px; flex:none; border-right:1px solid var(--dsw-alias-border-subtle,rgba(15,23,42,.12)); display:flex; flex-direction:column; min-height:0; }
      .wae-search { margin:8px 10px; padding:6px 9px; border-radius:8px; border:1px solid var(--dsw-alias-border-subtle,rgba(15,23,42,.12));
        background:transparent; color:inherit; font-size:12px; outline:none; }
      .wae-treebody { flex:1; overflow:auto; padding:0 0 12px; }
      .wae-row { display:flex; align-items:center; gap:6px; width:100%; border:0; background:transparent; color:inherit;
        text-align:left; padding:3px 8px; font-size:12px; cursor:pointer; border-radius:6px; }
      .wae-row:hover { background:var(--dsw-alias-bg-sunken,rgba(15,23,42,.05)); }
      .wae-row.on { background:var(--dsw-alias-accent-weak,rgba(47,111,237,.12)); }
      .wae-caret { width:10px; flex:none; color:var(--dsw-alias-text-secondary,#6b7280); font-size:10px; }
      .wae-dot { width:7px; height:7px; border-radius:2px; flex:none; }
      .wae-label { flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
      .wae-row.module .wae-label, .wae-row.group .wae-label { font-weight:600; }
      .wae-count { color:var(--dsw-alias-text-secondary,#6b7280); font-size:11px; font-variant-numeric:tabular-nums; flex:none; }
      .wae-main { flex:1; min-width:0; display:flex; flex-direction:column; }
      .wae-radial { flex:1; min-height:0; position:relative; background:var(--dsw-alias-bg-sunken,rgba(15,23,42,.02)); }
      .wae-radial svg { width:100%; height:100%; display:block; touch-action:none; cursor:grab; }
      .wae-radial svg:active { cursor:grabbing; }
      .wae-zoom { position:absolute; right:10px; bottom:10px; display:flex; gap:4px; }
      .wae-zoom button { width:26px; height:26px; border-radius:7px; border:1px solid var(--dsw-alias-border-subtle,rgba(15,23,42,.12));
        background:var(--dsw-alias-bg-elevated,#fff); color:inherit; cursor:pointer; font-size:13px; line-height:1; }
      .wae-hint { position:absolute; left:12px; top:10px; color:var(--dsw-alias-text-secondary,#6b7280); font-size:11px; max-width:70%; }
      .wae-detail { width:320px; flex:none; border-left:1px solid var(--dsw-alias-border-subtle,rgba(15,23,42,.12));
        overflow:auto; padding:12px 14px; background:var(--dsw-alias-bg-elevated,transparent); }
      .wae-detail h3 { margin:0 0 2px; font-size:13px; word-break:break-all; }
      .wae-detail .path { color:var(--dsw-alias-text-secondary,#6b7280); font-size:11px; word-break:break-all; margin-bottom:10px; }
      .wae-kv { display:flex; justify-content:space-between; gap:10px; font-size:11.5px; padding:3px 0; }
      .wae-kv span:last-child { color:var(--dsw-alias-text-secondary,#6b7280); font-variant-numeric:tabular-nums; }
      .wae-sub { font-size:11px; color:var(--dsw-alias-text-secondary,#6b7280); text-transform:uppercase; letter-spacing:.03em; margin:12px 0 4px; }
      .wae-chip { display:inline-block; border:1px solid var(--dsw-alias-border-subtle,rgba(15,23,42,.12)); border-radius:999px;
        padding:1px 8px; font-size:11px; margin:0 4px 4px 0; color:var(--dsw-alias-text-secondary,#6b7280); }
      .wae-pre { margin:8px 0 0; padding:8px; border-radius:8px; background:var(--dsw-alias-bg-sunken,rgba(15,23,42,.05));
        font-size:10.5px; line-height:1.5; max-height:300px; overflow:auto; white-space:pre; font-family:Consolas,monospace; }
      .wae-pad { padding:16px; overflow:auto; flex:1; min-height:0; }
      .wae-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(240px,1fr)); gap:12px; }
      .wae-card { border:1px solid var(--dsw-alias-border-subtle,rgba(15,23,42,.12)); border-radius:12px; padding:12px 14px;
        background:var(--dsw-alias-bg-elevated,#fff); }
      .wae-card h4 { margin:0 0 8px; font-size:12.5px; }
      .wae-actions { display:flex; gap:8px; flex-wrap:wrap; margin:14px 0; }
      .wae-log { margin-top:12px; padding:10px; border-radius:10px; background:var(--dsw-alias-bg-sunken,rgba(15,23,42,.05));
        font-family:Consolas,monospace; font-size:11px; line-height:1.5; max-height:320px; overflow:auto; white-space:pre-wrap; }
      .wae-table { width:100%; border-collapse:collapse; font-size:11.5px; }
      .wae-table th, .wae-table td { text-align:left; padding:5px 8px; border-bottom:1px solid var(--dsw-alias-border-subtle,rgba(15,23,42,.08)); }
      .wae-table th { color:var(--dsw-alias-text-secondary,#6b7280); font-weight:500; position:sticky; top:0; background:var(--dsw-alias-bg-base,#fff); }
      .wae-table td.num { text-align:right; font-variant-numeric:tabular-nums; }
      .wae-table tr.click { cursor:pointer; }
      .wae-table tr.click:hover { background:var(--dsw-alias-bg-sunken,rgba(15,23,42,.04)); }
      .wae-input { width:120px; padding:3px 6px; border-radius:6px; border:1px solid var(--dsw-alias-border-subtle,rgba(15,23,42,.2));
        background:transparent; color:inherit; font-size:11.5px; font-family:Consolas,monospace; }
      .wae-note { color:var(--dsw-alias-text-secondary,#6b7280); font-size:11.5px; line-height:1.6; }
      .wae-err { color:#c0392b; font-size:12px; padding:8px 0; }
      .wae-ok { color:#1e7f4f; font-size:11.5px; }
      .wae-overview { flex:1; min-height:0; overflow:hidden; position:relative; background:var(--dsw-alias-bg-sunken,rgba(15,23,42,.02)); }
      .wae-overview img { position:absolute; transform-origin:0 0; user-select:none; }
    `

    function statusBadge(value, label) {
      return h('span', { className: 'wae-chip' }, `${label}: ${value}`)
    }

    function bytesText(bytes) {
      if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
      const units = ['B', 'KB', 'MB', 'GB']
      let value = bytes
      let unit = 0
      while (value >= 1024 && unit < units.length - 1) {
        value /= 1024
        unit += 1
      }
      return `${value.toFixed(value >= 100 || unit === 0 ? 0 : 1)} ${units[unit]}`
    }

    function timeText(iso) {
      if (!iso) return '—'
      const date = new Date(iso)
      if (Number.isNaN(date.getTime())) return '—'
      return date.toLocaleString('zh-TW', { hour12: false })
    }

    function shortName(pkg) {
      const index = pkg.lastIndexOf('.')
      return index < 0 ? pkg : pkg.slice(index + 1)
    }

    function hashColor(text) {
      const palette = ['#2f6fed', '#2fa36b', '#c47f17', '#9b59b6', '#c0392b', '#16a085', '#7f6a4f', '#6b7280']
      let hash = 0
      for (let index = 0; index < text.length; index += 1) hash = (hash * 31 + text.charCodeAt(index)) >>> 0
      return palette[hash % palette.length]
    }

    async function fetchJson(url, options) {
      const response = await fetch(url, { headers: { accept: 'application/json' }, ...options })
      if (!response.ok) {
        let detail = ''
        try {
          detail = (await response.json()).error ?? ''
        } catch {
          detail = ''
        }
        throw new Error(`${response.status}${detail ? ` ${detail}` : ''}`)
      }
      return response.json()
    }

    function postJson(url, body) {
      return fetchJson(url, {
        method: 'POST',
        headers: { 'content-type': 'application/json', accept: 'application/json' },
        body: JSON.stringify(body),
      })
    }

    function Btn({ children, onClick, disabled, primary, title }) {
      return h('button', {
        type: 'button',
        className: `wae-btn${primary ? ' primary' : ''}`,
        onClick,
        disabled: disabled === true,
        title,
      }, children)
    }

    function TreeRow({ node, depth, expanded, toggle, selection, onSelect }) {
      const open = expanded.has(node.id)
      const leaf = node.children.length === 0
      const active = selection?.kind === 'file' ? selection.rel === node.path : selection?.kind === 'package' ? selection.pkg === node.pkg : false
      const dot = node.kind === 'test' ? '#c47f17'
        : node.kind === 'file' ? '#6b7280'
          : node.kind === 'asset' ? '#16a085'
            : node.kind === 'doc' ? '#7f6a4f'
              : hashColor(node.kind === 'group' || node.kind === 'module' ? node.label : (node.pkg ?? node.label))
      const count = leaf ? String(node.lines ?? 0) : String(node.count)
      return h('div', null,
        h('button', {
          type: 'button',
          className: `wae-row ${node.kind}${active ? ' on' : ''}`,
          style: { paddingLeft: `${6 + depth * 13}px` },
          onClick: () => {
            if (leaf) onSelect(node)
            else {
              toggle(node.id)
              if (node.kind === 'package' && node.pkg) onSelect(node)
            }
          },
          title: node.path ?? node.pkg ?? node.label,
        },
        h('span', { className: 'wae-caret' }, leaf ? '' : (open ? '▾' : '▸')),
        h('span', { className: 'wae-dot', style: { background: dot } }),
        h('span', { className: 'wae-label' }, node.label),
        h('span', { className: 'wae-count' }, count)),
        open && !leaf
          ? node.children.map((child) => h(TreeRow, {
            key: child.id,
            node: child,
            depth: depth + 1,
            expanded,
            toggle,
            selection,
            onSelect,
          }))
          : null)
    }

    function Tree({ graph, selection, onSelect, t }) {
      const [query, setQuery] = useState('')
      const [expanded, setExpanded] = useState(() => {
        const ids = new Set()
        const walk = (node, depth) => {
          if (depth <= 2) ids.add(node.id)
          for (const child of node.children) walk(child, depth + 1)
        }
        walk(graph.tree, 0)
        return ids
      })
      const toggle = useCallback((id) => {
        setExpanded((previous) => {
          const next = new Set(previous)
          if (next.has(id)) next.delete(id)
          else next.add(id)
          return next
        })
      }, [])
      const matches = useMemo(() => {
        const term = query.trim().toLowerCase()
        if (term.length < 2) return null
        return graph.files.filter((file) => file.rel.toLowerCase().includes(term)
          || file.pkg.toLowerCase().includes(term)
          || file.symbols.some((symbol) => symbol.name.toLowerCase().includes(term))).slice(0, 200)
      }, [graph.files, query])
      return h('div', { className: 'wae-tree' },
        h('input', {
          className: 'wae-search',
          placeholder: t('search'),
          value: query,
          onChange: (event) => setQuery(event.target.value),
          'aria-label': t('search'),
        }),
        h('div', { className: 'wae-treebody' },
          matches === null
            ? h(TreeRow, { node: graph.tree, depth: 0, expanded, toggle, selection, onSelect })
            : matches.length === 0
              ? h('div', { className: 'wae-note', style: { padding: '8px 12px' } }, t('empty'))
              : matches.map((file) => h('button', {
                key: file.id,
                type: 'button',
                className: `wae-row${selection?.kind === 'file' && selection.rel === file.rel ? ' on' : ''}`,
                style: { paddingLeft: '10px' },
                onClick: () => onSelect({ kind: 'file', rel: file.rel }),
                title: file.rel,
              },
              h('span', { className: 'wae-caret' }, ''),
              h('span', { className: 'wae-dot', style: { background: file.test ? '#c47f17' : '#6b7280' } }),
              h('span', { className: 'wae-label' }, file.name),
              h('span', { className: 'wae-count' }, String(file.lines)))),
      ))
    }

    function Radial({ graph, pkg, onSelect, t }) {
      const [view, setView] = useState({ scale: 1, x: 0, y: 0 })
      const svgRef = useRef(null)
      const dragRef = useRef(null)
      const outbound = useMemo(() => graph.edges.filter((edge) => edge.from === pkg), [graph.edges, pkg])
      const inbound = useMemo(() => graph.edges.filter((edge) => edge.to === pkg), [graph.edges, pkg])
      const stats = graph.packages.find((entry) => entry.name === pkg)
      const radius = 210
      const positions = new Map()
      const place = (list, side) => {
        const count = list.length
        list.forEach((edge, index) => {
          const ratio = count === 1 ? 0.5 : index / (count - 1)
          const angle = side === 'out' ? (-Math.PI / 2 + Math.PI * ratio) : (Math.PI / 2 + Math.PI * ratio)
          positions.set(side === 'out' ? edge.to : edge.from, {
            x: Math.cos(angle) * radius,
            y: Math.sin(angle) * radius,
          })
        })
      }
      place(outbound, 'out')
      place(inbound, 'in')

      useEffect(() => {
        const element = svgRef.current
        if (!element) return undefined
        const onWheel = (event) => {
          event.preventDefault()
          setView((previous) => ({ ...previous, scale: Math.min(2.6, Math.max(0.45, previous.scale * (event.deltaY > 0 ? 0.9 : 1.1))) }))
        }
        element.addEventListener('wheel', onWheel, { passive: false })
        return () => element.removeEventListener('wheel', onWheel)
      }, [])

      const onPointerDown = (event) => {
        dragRef.current = { x: event.clientX, y: event.clientY, startX: view.x, startY: view.y }
        event.currentTarget.setPointerCapture(event.pointerId)
      }
      const onPointerMove = (event) => {
        if (!dragRef.current) return
        setView((previous) => ({
          ...previous,
          x: dragRef.current.startX + (event.clientX - dragRef.current.x),
          y: dragRef.current.startY + (event.clientY - dragRef.current.y),
        }))
      }
      const onPointerUp = () => { dragRef.current = null }

      const edgeLine = (edge, other, side, index) => {
        const point = positions.get(other)
        if (!point) return null
        const color = side === 'out' ? '#2f6fed' : '#2fa36b'
        return h('g', { key: `${side}-${other}-${index}` },
          h('path', {
            d: `M 0 0 Q ${point.x / 2} ${point.y / 2 + (side === 'out' ? -18 : 18)} ${point.x} ${point.y}`,
            fill: 'none',
            stroke: color,
            strokeOpacity: 0.22 + Math.min(0.5, edge.count * 0.05),
            strokeWidth: 1 + Math.min(2.4, edge.count * 0.3),
          }),
          h('g', {
            transform: `translate(${point.x} ${point.y})`,
            style: { cursor: 'pointer' },
            onClick: () => onSelect({ kind: 'package', pkg: other }),
          },
          h('circle', { r: 5, fill: color, opacity: 0.9 }),
          h('text', {
            x: point.x >= 0 ? 10 : -10,
            y: 1,
            fill: 'var(--dsw-alias-text-primary,#1b1f24)',
            fontSize: 11,
            textAnchor: point.x >= 0 ? 'start' : 'end',
            dominantBaseline: 'middle',
          }, `${shortName(other)} (${edge.count})`)))
      }

      return h('div', { className: 'wae-radial' },
        h('div', { className: 'wae-hint' },
          `${t('center')}: ${pkg} · ${t('files')} ${stats?.files ?? 0} · ${t('lines')} ${stats?.lines ?? 0} · ${t('tests')} ${stats?.tests ?? 0}`),
        h('svg', {
          ref: svgRef,
          viewBox: '-360 -300 720 600',
          preserveAspectRatio: 'xMidYMid meet',
          onPointerDown,
          onPointerMove,
          onPointerUp,
          onPointerCancel: onPointerUp,
        },
        h('g', { transform: `translate(${view.x} ${view.y}) scale(${view.scale})` },
          outbound.map((edge, index) => edgeLine(edge, edge.to, 'out', index)),
          inbound.map((edge, index) => edgeLine(edge, edge.from, 'in', index)),
          h('circle', { r: 34, fill: 'var(--dsw-alias-accent,#2f6fed)', opacity: 0.14 }),
          h('circle', { r: 7, fill: 'var(--dsw-alias-accent,#2f6fed)' }),
          h('text', {
            y: -46,
            textAnchor: 'middle',
            fontSize: 13,
            fontWeight: 600,
            fill: 'var(--dsw-alias-text-primary,#1b1f24)',
          }, shortName(pkg)),
          h('text', {
            y: 52,
            textAnchor: 'middle',
            fontSize: 10.5,
            fill: 'var(--dsw-alias-text-secondary,#6b7280)',
          }, pkg))),
        h('div', { className: 'wae-zoom' },
          h('button', { type: 'button', onClick: () => setView((v) => ({ ...v, scale: Math.min(2.6, v.scale * 1.15) })), title: '放大' }, '+'),
          h('button', { type: 'button', onClick: () => setView((v) => ({ ...v, scale: Math.max(0.45, v.scale / 1.15) })), title: '縮小' }, '−'),
          h('button', { type: 'button', onClick: () => setView({ scale: 1, x: 0, y: 0 }), title: '重設' }, '1:1')),
        outbound.length + inbound.length === 0
          ? h('div', { className: 'wae-note', style: { position: 'absolute', left: 14, bottom: 12 } }, t('noDeps'))
          : null)
    }

    function Detail({ graph, selection, onSelect, t }) {
      const [preview, setPreview] = useState(null)
      const [previewError, setPreviewError] = useState(null)
      const [copyNote, setCopyNote] = useState('')
      const selectionKey = selection === null ? '' : selection.kind === 'file' ? selection.rel : selection.pkg
      useEffect(() => {
        setPreview(null)
        setPreviewError(null)
        setCopyNote('')
      }, [selectionKey])

      if (!selection) return h('div', { className: 'wae-detail' }, h('div', { className: 'wae-note' }, t('noSelection')))

      if (selection.kind === 'package') {
        const stats = graph.packages.find((entry) => entry.name === selection.pkg)
        const files = graph.files.filter((file) => file.pkg === selection.pkg)
        const outbound = graph.edges.filter((edge) => edge.from === selection.pkg)
        const inbound = graph.edges.filter((edge) => edge.to === selection.pkg)
        return h('div', { className: 'wae-detail' },
          h('h3', null, shortName(selection.pkg)),
          h('div', { className: 'path' }, selection.pkg),
          h('div', { className: 'wae-kv' }, h('span', null, t('files')), h('span', null, String(stats?.files ?? files.length))),
          h('div', { className: 'wae-kv' }, h('span', null, t('lines')), h('span', null, String(stats?.lines ?? 0))),
          h('div', { className: 'wae-kv' }, h('span', null, t('tests')), h('span', null, String(stats?.tests ?? 0))),
          h('div', { className: 'wae-sub' }, t('dependencies')),
          outbound.length === 0 ? h('div', { className: 'wae-note' }, '—') : outbound.map((edge) => h('span', {
            key: edge.to,
            className: 'wae-chip',
            style: { cursor: 'pointer' },
            onClick: () => onSelect({ kind: 'package', pkg: edge.to }),
          }, `${shortName(edge.to)} (${edge.count})`)),
          h('div', { className: 'wae-sub' }, t('dependents')),
          inbound.length === 0 ? h('div', { className: 'wae-note' }, '—') : inbound.map((edge) => h('span', {
            key: edge.from,
            className: 'wae-chip',
            style: { cursor: 'pointer' },
            onClick: () => onSelect({ kind: 'package', pkg: edge.from }),
          }, `${shortName(edge.from)} (${edge.count})`)),
          h('div', { className: 'wae-sub' }, t('files')),
          files.slice(0, 40).map((file) => h('div', {
            key: file.rel,
            className: 'wae-kv',
            style: { cursor: 'pointer' },
            onClick: () => onSelect({ kind: 'file', rel: file.rel }),
          },
          h('span', { style: { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }, title: file.rel }, file.name),
          h('span', null, String(file.lines)))))
      }

      const file = graph.files.find((entry) => entry.rel === selection.rel)
      if (!file) return h('div', { className: 'wae-detail' }, h('div', { className: 'wae-note' }, t('empty')))
      const tests = graph.files.filter((entry) => entry.test && entry.pkg === file.pkg)
      const copy = async () => {
        const absolute = `${graph.root}\\${file.rel.replace(/\//g, '\\')}`
        try {
          await navigator.clipboard.writeText(absolute)
          setCopyNote(t('copied'))
        } catch {
          setCopyNote(absolute)
        }
      }
      const loadPreview = async () => {
        setPreviewError(null)
        setPreview(t('loadingPreview'))
        try {
          const response = await fetch(`${API}/file?path=${encodeURIComponent(file.rel)}`)
          if (!response.ok) throw new Error(String(response.status))
          setPreview(await response.text())
        } catch (error) {
          setPreview(null)
          setPreviewError(String(error.message ?? error))
        }
      }
      return h('div', { className: 'wae-detail' },
        h('h3', null, file.name),
        h('div', { className: 'path' }, file.rel),
        h('div', { className: 'wae-kv' }, h('span', null, t('lines')), h('span', null, String(file.lines))),
        h('div', { className: 'wae-kv' }, h('span', null, 'package'), h('span', null, file.pkg)),
        file.symbols.length > 0
          ? h('div', null,
            h('div', { className: 'wae-sub' }, `${t('symbols')} (${file.symbols.length})`),
            file.symbols.slice(0, 20).map((symbol, index) => h('div', { key: `${symbol.name}-${index}`, className: 'wae-kv' },
              h('span', null, `${symbol.kind} ${symbol.name}`),
              h('span', null, String(symbol.line)))))
          : null,
        file.deps.length > 0
          ? h('div', null,
            h('div', { className: 'wae-sub' }, t('dependencies')),
            file.deps.map((dep) => h('span', { key: dep, className: 'wae-chip' }, shortName(dep))))
          : null,
        tests.length > 0
          ? h('div', null,
            h('div', { className: 'wae-sub' }, `${t('testFiles')} (${tests.length})`),
            tests.slice(0, 12).map((test) => h('div', { key: test.rel, className: 'wae-kv' },
              h('span', { style: { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }, title: test.rel }, test.name),
              h('span', null, String(test.lines)))))
          : null,
        h('div', { className: 'wae-actions' },
          h(Btn, { onClick: copy }, t('copyPath')),
          h(Btn, { onClick: loadPreview }, t('preview'))),
        copyNote ? h('div', { className: 'wae-ok' }, copyNote) : null,
        previewError ? h('div', { className: 'wae-err' }, previewError) : null,
        preview !== null ? h('pre', { className: 'wae-pre' }, preview) : null)
    }

    function MapTab({ graph, t }) {
      const [selection, setSelection] = useState(null)
      const selectedPkg = selection === null
        ? graph.packages[0]?.name ?? ''
        : selection.kind === 'file'
          ? (graph.files.find((file) => file.rel === selection.rel)?.pkg ?? graph.packages[0]?.name ?? '')
          : selection.pkg
      const onSelect = (node) => {
        if (node.kind === 'file' || node.kind === 'test') setSelection({ kind: 'file', rel: node.path })
        else if (node.pkg) setSelection({ kind: 'package', pkg: node.pkg })
      }
      return h('div', { className: 'wae-map' },
        h(Tree, { graph, selection, onSelect, t }),
        h('div', { className: 'wae-main' },
          h(Radial, { graph, pkg: selectedPkg, onSelect: setSelection, t })),
        h(Detail, { graph, selection, onSelect: setSelection, t }))
    }

    function OverviewTab({ t }) {
      const [view, setView] = useState({ scale: 0.42, x: 24, y: 24 })
      const dragRef = useRef(null)
      return h('div', { className: 'wae-overview' },
        h('div', { className: 'wae-hint' }, t('overviewNote')),
        h('img', {
          src: `${API}/overview.svg`,
          alt: t('tabOverview'),
          style: { transform: `translate(${view.x}px, ${view.y}px) scale(${view.scale})` },
          onPointerDown: (event) => {
            dragRef.current = { x: event.clientX, y: event.clientY, startX: view.x, startY: view.y }
            event.currentTarget.setPointerCapture(event.pointerId)
          },
          onPointerMove: (event) => {
            if (!dragRef.current) return
            setView((previous) => ({
              ...previous,
              x: dragRef.current.startX + (event.clientX - dragRef.current.x),
              y: dragRef.current.startY + (event.clientY - dragRef.current.y),
            }))
          },
          onPointerUp: () => { dragRef.current = null },
        }),
        h('div', { className: 'wae-zoom' },
          h('button', { type: 'button', onClick: () => setView((v) => ({ ...v, scale: Math.min(2.2, v.scale * 1.15) })) }, '+'),
          h('button', { type: 'button', onClick: () => setView((v) => ({ ...v, scale: Math.max(0.12, v.scale / 1.15) })) }, '−'),
          h('button', { type: 'button', onClick: () => setView({ scale: 0.42, x: 24, y: 24 }) }, '1:1')))
    }

    function BuildTab({ t }) {
      const [status, setStatus] = useState(null)
      const [error, setError] = useState(null)
      const [job, setJob] = useState(null)
      const load = useCallback(async () => {
        try {
          setStatus(await fetchJson(`${API}/status`))
          setError(null)
        } catch (failure) {
          setError(String(failure.message ?? failure))
        }
      }, [])
      useEffect(() => { void load() }, [load])
      useEffect(() => {
        if (!job || job.status !== 'running') return undefined
        const timer = setInterval(() => {
          fetchJson(`${API}/job?id=${encodeURIComponent(job.id)}`)
            .then((next) => { if (next) setJob(next) })
            .catch(() => {})
        }, 1500)
        return () => clearInterval(timer)
      }, [job?.id, job?.status])
      useEffect(() => {
        if (job && job.status !== 'running') void load()
      }, [job?.status, load])
      const run = async (action) => {
        try {
          setJob(await postJson(`${API}/action`, { action }))
          setError(null)
        } catch (failure) {
          setError(String(failure.message ?? failure))
        }
      }
      if (status === null) {
        return h('div', { className: 'wae-pad' }, error ? h('div', { className: 'wae-err' }, error) : h('div', { className: 'wae-note' }, t('loadingPreview')))
      }
      const apk = status.apk.full
      const lite = status.apk.lite
      const tests = status.tests
      const device = status.device
      const accessibility = device?.accessibility
      const jobState = job === null ? '' : job.status === 'running' ? t('jobRunning') : job.status === 'done' ? t('jobDone') : t('jobFailed')
      return h('div', { className: 'wae-pad' },
        h('div', { className: 'wae-actions' },
          h(Btn, { onClick: load }, t('reload')),
          h(Btn, { onClick: () => run('test'), primary: true, disabled: job?.status === 'running' }, t('runTest')),
          h(Btn, { onClick: () => run('build'), disabled: job?.status === 'running' }, t('runBuild')),
          h(Btn, { onClick: () => run('install'), disabled: job?.status === 'running' }, t('install')),
          h(Btn, { onClick: () => run('rebind'), disabled: job?.status === 'running' }, t('rebind'))),
        error ? h('div', { className: 'wae-err' }, error) : null,
        h('div', { className: 'wae-grid' },
          h('div', { className: 'wae-card' },
            h('h4', null, t('apkFull')),
            apk === null ? h('div', { className: 'wae-note' }, t('noApk')) : h('div', null,
              h('div', { className: 'wae-kv' }, h('span', null, t('size')), h('span', null, bytesText(apk.bytes))),
              h('div', { className: 'wae-kv' }, h('span', null, 'mtime'), h('span', null, timeText(apk.mtime)))),
            h('div', { className: 'wae-kv' }, h('span', null, t('apkLite')), h('span', null, lite ? bytesText(lite.bytes) : t('noApk')))),
          h('div', { className: 'wae-card' },
            h('h4', null, t('unitTests')),
            tests === null ? h('div', { className: 'wae-note' }, t('noTests')) : h('div', null,
              h('div', { className: 'wae-kv' }, h('span', null, 'tests'), h('span', null, String(tests.tests))),
              h('div', { className: 'wae-kv' }, h('span', null, 'failures'), h('span', null, String(tests.failures + tests.errors))),
              h('div', { className: 'wae-kv' }, h('span', null, 'skipped'), h('span', null, String(tests.skipped))),
              h('div', { className: 'wae-kv' }, h('span', null, 'mtime'), h('span', null, timeText(tests.mtime))),
              tests.failed.length > 0 ? h('div', null,
                h('div', { className: 'wae-sub' }, t('failedTests')),
                tests.failed.map((name) => h('div', { key: name, className: 'wae-note' }, name))) : null)),
          h('div', { className: 'wae-card' },
            h('h4', null, t('device')),
            device === null || device.adb === false
              ? h('div', { className: 'wae-note' }, t('noDevice'))
              : device.serial === null
                ? h('div', { className: 'wae-note' }, t('noDevice'))
                : h('div', null,
                  h('div', { className: 'wae-kv' }, h('span', null, 'serial'), h('span', null, device.serial)),
                  accessibility === null ? null : h('div', null,
                    h('div', { className: 'wae-kv' }, h('span', null, 'accessibility'),
                      h('span', null, accessibility.bound ? t('bound') : t('unbound'))),
                    h('div', { className: 'wae-kv' }, h('span', null, 'enabled'),
                      h('span', null, accessibility.enabled ? t('enabled') : t('disabled'))))))),
        job === null ? null : h('div', null,
          h('div', { className: 'wae-sub' }, `${job.label} · ${jobState}`),
          h('pre', { className: 'wae-log' }, job.lines.join('\n'))))
    }

    function DataTab({ graph, t }) {
      const [selected, setSelected] = useState(null)
      const asset = graph.assets.find((entry) => entry.name === selected)
      return h('div', { className: 'wae-pad' },
        h('div', { className: 'wae-note' }, `${graph.assets.length} ${t('datasets')}`),
        h('table', { className: 'wae-table' },
          h('thead', null, h('tr', null,
            h('th', null, t('datasetName')),
            h('th', null, t('rows')),
            h('th', null, t('columns')),
            h('th', null, t('size')),
            h('th', null, t('bom')),
            h('th', null, t('refs')))),
          h('tbody', null, graph.assets.map((entry) => h('tr', {
            key: entry.name,
            className: 'click',
            onClick: () => setSelected(entry.name),
            style: selected === entry.name ? { background: 'var(--dsw-alias-bg-sunken,rgba(15,23,42,.05))' } : null,
          },
          h('td', null, entry.name),
          h('td', { className: 'num' }, String(entry.rows)),
          h('td', { className: 'num' }, String(entry.columns)),
          h('td', { className: 'num' }, bytesText(entry.bytes)),
          h('td', null, entry.bom ? t('bomYes') : t('bomNo')),
          h('td', { className: 'num' }, String(entry.refs.length)))))),
        asset === undefined
          ? null
          : h('div', { className: 'wae-card', style: { marginTop: 12 } },
            h('h4', null, asset.name),
            asset.refs.length === 0
              ? h('div', { className: 'wae-note' }, t('noRefs'))
              : asset.refs.map((ref) => h('div', { key: ref, className: 'wae-kv' }, h('span', { className: 'path' }, ref)))))
    }

    function TunablesTab({ graph, t }) {
      const [rows, setRows] = useState(graph.tunables)
      const [draft, setDraft] = useState({})
      const [message, setMessage] = useState(null)
      const [error, setError] = useState(null)
      const save = async (row) => {
        const value = draft[row.key] ?? row.value
        try {
          await postJson(`${API}/tunables`, { key: row.key, value })
          setRows((previous) => previous.map((entry) => entry.key === row.key ? { ...entry, value } : entry))
          setDraft((previous) => { const next = { ...previous }; delete next[row.key]; return next })
          setMessage(t('saved'))
          setError(null)
        } catch (failure) {
          setError(String(failure.message ?? failure))
          setMessage(null)
        }
      }
      return h('div', { className: 'wae-pad' },
        h('div', { className: 'wae-note' }, t('tunablesNote')),
        message ? h('div', { className: 'wae-ok' }, message) : null,
        error ? h('div', { className: 'wae-err' }, error) : null,
        h('table', { className: 'wae-table' },
          h('thead', null, h('tr', null,
            h('th', null, 'key'),
            h('th', null, 'value'),
            h('th', null, '說明'))),
          h('tbody', null, rows.map((row) => h('tr', { key: row.key },
            h('td', { style: { fontFamily: 'Consolas,monospace' } }, row.key),
            h('td', null, h('input', {
              className: 'wae-input',
              value: draft[row.key] ?? row.value,
              onChange: (event) => setDraft((previous) => ({ ...previous, [row.key]: event.target.value })),
              'aria-label': row.key,
            })),
            h('td', null,
              h('span', { className: 'wae-note' }, row.description),
              draft[row.key] !== undefined ? h('span', null, ' ',
                h(Btn, { onClick: () => save(row) }, t('save'))) : null))))))
    }

    function DebtTab({ graph, t }) {
      return h('div', { className: 'wae-pad' },
        h('div', { className: 'wae-note' }, t('debtNote')),
        h('div', { className: 'wae-grid', style: { marginTop: 12 } },
          h('div', { className: 'wae-card' }, h('h4', null, t('runCatching')), h('div', { style: { fontSize: 26, fontWeight: 600 } }, String(graph.debt.runCatching))),
          h('div', { className: 'wae-card' }, h('h4', null, t('todo')), h('div', { style: { fontSize: 26, fontWeight: 600 } }, String(graph.debt.todo))),
          h('div', { className: 'wae-card' }, h('h4', null, t('emoji')), h('div', { style: { fontSize: 26, fontWeight: 600 } }, String(graph.debt.emoji))),
          h('div', { className: 'wae-card' }, h('h4', null, t('maxLines')), h('div', { style: { fontSize: 26, fontWeight: 600 } }, String(graph.debt.maxLines)))),
        h('h4', { style: { marginTop: 18 } }, t('largest')),
        h('table', { className: 'wae-table' },
          h('tbody', null, graph.debt.largest.map((file) => h('tr', { key: file.rel },
            h('td', null, file.rel),
            h('td', { className: 'num' }, String(file.lines)))))))
    }

    function WaePanel({ t = (key) => zh[key] ?? key }) {
      const [graph, setGraph] = useState(null)
      const [error, setError] = useState(null)
      const [loading, setLoading] = useState(false)
      const [tab, setTab] = useState('map')
      const load = useCallback(async (force) => {
        setLoading(true)
        try {
          setGraph(await fetchJson(`${API}/graph${force ? '?refresh=1' : ''}`))
          setError(null)
        } catch (failure) {
          setError(String(failure.message ?? failure))
        } finally {
          setLoading(false)
        }
      }, [])
      useEffect(() => { void load(false) }, [load])

      const tabs = [
        ['map', t('tabMap')],
        ['overview', t('tabOverview')],
        ['build', t('tabBuild')],
        ['data', t('tabData')],
        ['tunables', t('tabTunables')],
        ['debt', t('tabDebt')],
      ]
      return h('div', { className: 'wae-root' },
        h('style', null, STYLE),
        h('div', { className: 'wae-head' },
          h('div', { className: 'wae-title' }, t('panel')),
          h('div', { className: 'wae-rootpath' }, graph === null ? (loading ? t('scanning') : '') : `${graph.root} · ${timeText(graph.scannedAt)} · ${graph.stats.files} ${t('files')} / ${graph.stats.lines} ${t('lines')}`),
          h(Btn, { onClick: () => load(true), disabled: loading }, loading ? t('scanning') : t('refresh'))),
        error ? h('div', { className: 'wae-err', style: { padding: '8px 16px' } }, error) : null,
        h('div', { className: 'wae-tabs' }, tabs.map(([id, label]) => h('button', {
          key: id,
          type: 'button',
          className: `wae-tab${tab === id ? ' on' : ''}`,
          onClick: () => setTab(id),
        }, label))),
        graph === null
          ? h('div', { className: 'wae-pad' }, h('div', { className: 'wae-note' }, loading ? t('scanning') : t('empty')))
          : h('div', { className: 'wae-body' },
            tab === 'map' ? h(MapTab, { graph, t }) : null,
            tab === 'overview' ? h(OverviewTab, { t }) : null,
            tab === 'build' ? h(BuildTab, { t }) : null,
            tab === 'data' ? h(DataTab, { graph, t }) : null,
            tab === 'tunables' ? h(TunablesTab, { graph, t }) : null,
            tab === 'debt' ? h(DebtTab, { graph, t }) : null))
    }

    function PanelIcon({ size }) {
      const edge = size ?? 18
      return h('svg', {
        width: edge,
        height: edge,
        viewBox: '0 0 24 24',
        fill: 'none',
        stroke: 'currentColor',
        strokeWidth: 1.9,
        strokeLinecap: 'round',
        strokeLinejoin: 'round',
        'aria-hidden': true,
      },
      h('circle', { cx: 5, cy: 12, r: 2 }),
      h('circle', { cx: 18, cy: 5.5, r: 2 }),
      h('circle', { cx: 18, cy: 18.5, r: 2 }),
      h('path', { d: 'M7 12h4.5c2 0 2-6.5 4.5-6.5' }),
      h('path', { d: 'M7 12h4.5c2 0 2 6.5 4.5 6.5' }))
    }

    class Boundary extends React.Component {
      constructor(props) {
        super(props)
        this.state = { error: null }
      }

      static getDerivedStateFromError(error) {
        return { error }
      }

      render() {
        if (this.state.error !== null) {
          return h('div', { className: 'wae-pad' },
            h('h3', null, 'WAE 地圖面板發生錯誤'),
            h('pre', { className: 'wae-pre' }, String(this.state.error?.stack ?? this.state.error)))
        }
        return this.props.children
      }
    }

    function WaePanelBoundary(props) {
      return h(Boundary, null, h(WaePanel, props))
    }

    return {
      inject: ['slots', 'locale'],
      apply(ctx) {
        ctx.effect(() => ctx.locale.register(NS, { zh, en }), 'wae-kit: dictionaries')
        const t = ctx.locale.bind(NS)
        const disposers = [
          ctx.slots.inject('main', () => ctx.slots.register({
            name: 'main',
            key: PANEL_ID,
            locale: NS,
            inject: () => ({ t }),
          }, WaePanelBoundary)),
          ctx.slots.inject('sidebar.panellist', () => ctx.slots.register({
            name: 'sidebar.panellist',
            id: PANEL_ID,
            order: 2,
            label: () => t('panel'),
            locale: NS,
          }, PanelIcon)),
        ]
        return () => {
          for (const dispose of disposers) {
            if (typeof dispose === 'function') dispose()
          }
        }
      },
    }
  },
})
