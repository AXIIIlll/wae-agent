/**
 * WAE project scanner.
 *
 * Reads the Kotlin sources, TSV datasets, docs and tunables of the configured
 * workspace and returns one JSON snapshot for the map panel and the agent tool.
 * Parsing is deliberately approximate: packages and imports are reliable,
 * top-level declarations are a line-based heuristic, never a compiler.
 */
import { copyFileSync, existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { basename, join, resolve } from 'node:path'

const SOURCES = [
  { module: 'app', area: 'main', dir: 'app/src/main/java', base: 'com.wae.agent', label: 'app · 主程式' },
  { module: 'app', area: 'test', dir: 'app/src/test/java', base: 'com.wae.agent', label: 'app · 測試' },
  { module: 'app', area: 'androidTest', dir: 'app/src/androidTest/java', base: 'com.wae.agent', label: 'app · 裝置測試' },
  { module: 'core', area: 'main', dir: 'core/src/main/kotlin', base: 'com.wae.core', label: 'core · 主程式' },
  { module: 'core', area: 'test', dir: 'core/src/test/kotlin', base: 'com.wae.core', label: 'core · 測試' },
]

const ASSET_DIR = 'app/src/main/assets/open_source_ai_databases'
const DOC_DIR = 'docs'
const TUNABLES = 'app/src/main/assets/open_source_ai_databases/ui_tunables.tsv'
const OVERVIEW_CANDIDATES = [
  'wae-mindmap-app/assets/WAE-flow-mindmap.svg',
  'design/wae-mindmap/WAE-flow-mindmap.svg',
]

const SYMBOL_RE = new RegExp(
  '^(?:(?:public|internal|private|protected|open|final|abstract|sealed|data|enum|annotation|inline|value|suspend|operator|infix|tailrec|external|const|lateinit|override|actual|expect)\\s+)*'
  + '(?:(class|interface|object|typealias)\\s+([A-Za-z_][A-Za-z0-9_]*)'
  + '|fun\\s+(?:<[^>]*>\\s*)?(?:[A-Za-z_][A-Za-z0-9_.]*\\.)?([A-Za-z_][A-Za-z0-9_]*)'
  + '|(val|var)\\s+([A-Za-z_][A-Za-z0-9_]*))',
)

const EMOJI_RE = /\p{Extended_Pictographic}/gu
const TSV_RE = /([A-Za-z0-9_-]+\.tsv)/g

let cache = { root: '', at: 0, data: null }

function walk(dir, extension, out) {
  let entries
  try {
    entries = readdirSync(dir, { withFileTypes: true })
  } catch {
    return
  }
  for (const entry of entries) {
    if (entry.name.startsWith('.') || entry.name === 'build') continue
    const full = join(dir, entry.name)
    if (entry.isDirectory()) walk(full, extension, out)
    else if (entry.name.endsWith(extension)) out.push(full)
  }
}

function parseKotlin(text) {
  const lines = text.split(/\r?\n/)
  let pkg = ''
  const imports = []
  const symbols = []
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index]
    if (pkg === '') {
      const match = /^package\s+([A-Za-z0-9_.]+)/.exec(line)
      if (match) pkg = match[1]
    }
    if (line.startsWith('import ')) {
      const match = /^import\s+([A-Za-z0-9_.*]+)/.exec(line)
      if (match) imports.push(match[1])
    }
    if (symbols.length < 200 && line.length > 0 && !/\s/.test(line[0])
      && !line.startsWith('//') && !line.startsWith('/*') && !line.startsWith('*') && !line.startsWith('@')) {
      const match = SYMBOL_RE.exec(line)
      if (match) {
        const kind = match[1] ?? (match[3] !== undefined ? 'fun' : 'val')
        const name = match[2] ?? match[4] ?? match[6]
        if (name) symbols.push({ kind, name, line: index + 1 })
      }
    }
  }
  return { pkg, imports, symbols, lines: lines.length }
}

function importPackage(importName) {
  if (importName.endsWith('.*')) return importName.slice(0, -2)
  const cut = importName.lastIndexOf('.')
  return cut < 0 ? '' : importName.slice(0, cut)
}

function resolvePackage(candidate, known) {
  let current = candidate
  while (current) {
    if (known.has(current)) return current
    const cut = current.lastIndexOf('.')
    if (cut < 0) return null
    current = current.slice(0, cut)
  }
  return null
}

function newNode(id, label, kind, extra = {}) {
  return { id, label, kind, count: 0, lines: 0, children: [], ...extra }
}

function aggregate(node) {
  let count = 0
  let lines = 0
  for (const child of node.children) {
    aggregate(child)
    count += child.count
    lines += child.lines
  }
  if (node.children.length > 0) {
    node.count = count
    node.lines = lines
  } else if (node.kind === 'file' || node.kind === 'test' || node.kind === 'asset' || node.kind === 'doc') {
    node.count = 1
  }
  return node
}

function sortTree(node) {
  if (node.children.length > 0) {
    node.children.sort((left, right) => {
      const rank = { module: 0, group: 1, package: 2, file: 3, test: 3, asset: 3, doc: 3, assets: 4, docs: 5 }
      const delta = (rank[left.kind] ?? 6) - (rank[right.kind] ?? 6)
      if (delta !== 0) return delta
      return left.label.localeCompare(right.label)
    })
    for (const child of node.children) sortTree(child)
  }
}

function countLines(text) {
  if (text === '') return 0
  let lines = 1
  for (let index = 0; index < text.length; index += 1) if (text.charCodeAt(index) === 10) lines += 1
  return text.endsWith('\n') ? lines - 1 : lines
}

export function readTunables(root) {
  const path = join(root, TUNABLES)
  if (!existsSync(path)) return []
  const text = readFileSync(path, 'utf8').replace(/^\uFEFF/, '')
  const rows = []
  for (const line of text.split(/\r?\n/)) {
    if (line === '' || line.startsWith('#')) continue
    const parts = line.split('\t')
    if (parts.length < 2) continue
    rows.push({ key: parts[0], value: parts[1], description: parts.slice(2).join('\t') })
  }
  return rows
}

/** Rewrite one tunable value in place, keep every other line, and back the file up first. */
export function saveTunable(root, key, value) {
  if (!/^[A-Za-z0-9_.-]+$/.test(key)) throw new Error(`不允許的 key：${key}`)
  if (/[\t\r\n]/.test(value)) throw new Error('值不能包含 Tab 或換行')
  const path = join(root, TUNABLES)
  if (!existsSync(path)) throw new Error(`找不到 ${TUNABLES}`)
  const original = readFileSync(path, 'utf8')
  const bom = original.startsWith('\uFEFF')
  const trailing = original.endsWith('\n')
  const lines = original.replace(/^\uFEFF/, '').split(/\r?\n/)
  if (trailing) lines.pop()
  let found = false
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index]
    if (line === '' || line.startsWith('#')) continue
    const parts = line.split('\t')
    if (parts[0] !== key) continue
    parts[1] = value
    lines[index] = parts.join('\t')
    found = true
    break
  }
  if (!found) throw new Error(`ui_tunables.tsv 沒有 ${key}`)
  const stamp = new Date().toISOString().replace(/[:.]/g, '-')
  const backup = `${path}.bak-${stamp}`
  copyFileSync(path, backup)
  writeFileSync(path, lines.join('\n') + (trailing ? '\n' : ''), 'utf8')
  return { backup: basename(backup), value, bomRemoved: bom }
}

export function overviewPath(root) {
  for (const candidate of OVERVIEW_CANDIDATES) {
    const path = join(root, candidate)
    if (existsSync(path) && statSync(path).isFile()) return path
  }
  return null
}

export function scanProject(options = {}) {
  const root = resolve(options.root ?? process.cwd())
  const now = Date.now()
  if (!options.force && cache.data !== null && cache.root === root && now - cache.at < 15000) return cache.data
  const started = now

  const files = []
  const knownPackages = new Set()
  for (const source of SOURCES) {
    const dir = join(root, source.dir)
    if (!existsSync(dir)) continue
    const absolute = []
    walk(dir, '.kt', absolute)
    for (const abs of absolute) {
      let text = ''
      try {
        text = readFileSync(abs, 'utf8').replace(/^\uFEFF/, '')
      } catch {
        continue
      }
      const rel = abs.slice(root.length + 1).replace(/\\/g, '/')
      const parsed = parseKotlin(text)
      const pkg = parsed.pkg || `${source.base}.unknown`
      knownPackages.add(pkg)
      files.push({
        id: rel,
        rel,
        abs,
        name: basename(abs),
        module: source.module,
        area: source.area,
        pkg,
        base: source.base,
        lines: parsed.lines,
        symbols: parsed.symbols,
        imports: parsed.imports.length,
        deps: [],
        test: source.area !== 'main',
        todo: (text.match(/\b(TODO|FIXME|HACK)\b/g) ?? []).length,
        runCatching: (text.match(/runCatching/g) ?? []).length,
        emoji: (text.match(EMOJI_RE) ?? []).length,
        text,
      })
    }
  }

  // Resolve com.wae.* imports to packages and build the package graph. The
  // import lines were not kept on the file record, so parse them again from the
  // cached text (cheap; about 500 small files).
  const edgeMap = new Map()
  const packageStats = new Map()
  for (const file of files) {
    const deps = new Set()
    for (const line of file.text.split(/\r?\n/)) {
      if (!line.startsWith('import com.wae.')) continue
      const importName = line.slice(7).trim().split(/\s+/)[0]
      const candidate = importPackage(importName)
      const pkg = resolvePackage(candidate, knownPackages)
      if (pkg && pkg !== file.pkg) deps.add(pkg)
    }
    file.deps = [...deps].sort()
    for (const target of file.deps) {
      const key = `${file.pkg}\u0000${target}`
      edgeMap.set(key, (edgeMap.get(key) ?? 0) + 1)
    }
    const stats = packageStats.get(file.pkg) ?? { name: file.pkg, files: 0, lines: 0, tests: 0, module: file.module, area: file.area }
    stats.files += 1
    stats.lines += file.lines
    if (file.test) stats.tests += 1
    packageStats.set(file.pkg, stats)
  }

  const tree = newNode('root', 'WAE', 'root')
  const packageNodes = new Map()
  for (const source of SOURCES) {
    const sourceFiles = files.filter((file) => file.module === source.module && file.area === source.area)
    if (sourceFiles.length === 0) continue
    const moduleNode = newNode(`module:${source.module}:${source.area}`, source.label, 'module')
    moduleNode.test = source.area !== 'main'
    const groups = new Map()
    for (const file of sourceFiles) {
      const relativeParts = file.pkg.startsWith(`${source.base}.`)
        ? file.pkg.slice(source.base.length + 1).split('.')
        : file.pkg === source.base ? [] : file.pkg.split('.')
      const groupName = relativeParts[0] ?? '(root)'
      let groupNode = groups.get(groupName)
      if (!groupNode) {
        groupNode = newNode(`group:${source.module}:${source.area}:${groupName}`, groupName, 'group')
        groups.set(groupName, groupNode)
        moduleNode.children.push(groupNode)
      }
      let parent = groupNode
      let prefix = groupName === '(root)' ? source.base : `${source.base}.${groupName}`
      for (const part of relativeParts.slice(1)) {
        const nodeId = `pkg:${source.module}:${source.area}:${prefix}`
        let node = packageNodes.get(nodeId)
        if (!node) {
          node = newNode(nodeId, part, 'package', { pkg: prefix })
          packageNodes.set(nodeId, node)
          parent.children.push(node)
        }
        parent = node
        prefix = `${prefix}.${part}`
      }
      parent.children.push(newNode(file.rel, file.name, file.test ? 'test' : 'file', {
        path: file.rel,
        pkg: file.pkg,
        lines: file.lines,
        symbols: file.symbols.length,
        deps: file.deps.length,
        todo: file.todo,
        test: file.test,
      }))
    }
    tree.children.push(moduleNode)
  }

  // Datasets.
  const assetDir = join(root, ASSET_DIR)
  const assetNodes = []
  const mentioned = new Map()
  for (const file of files) {
    for (const match of file.text.matchAll(TSV_RE)) {
      const name = match[1]
      const list = mentioned.get(name) ?? []
      if (list.length < 12 && !list.includes(file.rel)) list.push(file.rel)
      mentioned.set(name, list)
    }
    delete file.text
  }
  if (existsSync(assetDir)) {
    for (const name of readdirSync(assetDir).sort()) {
      if (!name.endsWith('.tsv')) continue
      const abs = join(assetDir, name)
      let text = ''
      try {
        text = readFileSync(abs, 'utf8')
      } catch {
        continue
      }
      const bom = text.startsWith('\uFEFF')
      const lines = text.replace(/^\uFEFF/, '').split(/\r?\n/)
      let rows = 0
      let columns = 1
      for (const line of lines) {
        if (line === '' || line.startsWith('#')) continue
        rows += 1
        const cols = line.split('\t').length
        if (cols > columns) columns = cols
      }
      assetNodes.push(newNode(`asset:${name}`, name, 'asset', {
        bytes: statSync(abs).size,
        rows,
        columns,
        bom,
        refs: mentioned.get(name) ?? [],
      }))
    }
  }
  if (assetNodes.length > 0) {
    const assetsNode = newNode('assets', '資料表（TSV）', 'assets')
    assetsNode.children = assetNodes
    tree.children.push(assetsNode)
  }

  // Docs.
  const docNodes = []
  const docsDir = join(root, DOC_DIR)
  const docFiles = []
  if (existsSync(docsDir)) walk(docsDir, '.md', docFiles)
  for (const abs of docFiles.sort()) {
    let text = ''
    try {
      text = readFileSync(abs, 'utf8')
    } catch {
      continue
    }
    const heading = /^#\s+(.+)$/m.exec(text)
    const rel = abs.slice(root.length + 1).replace(/\\/g, '/')
    docNodes.push(newNode(`doc:${rel}`, heading ? heading[1].trim() : basename(abs), 'doc', {
      path: rel,
      lines: countLines(text),
    }))
  }
  if (docNodes.length > 0) {
    const docsNode = newNode('docs', '文件（Markdown）', 'docs')
    docsNode.children = docNodes
    tree.children.push(docsNode)
  }

  aggregate(tree)
  sortTree(tree)

  const edges = [...edgeMap.entries()].map(([key, count]) => {
    const [from, to] = key.split('\u0000')
    return { from, to, count }
  })

  const mainFiles = files.filter((file) => file.area === 'main')
  const debt = {
    runCatching: mainFiles.reduce((sum, file) => sum + file.runCatching, 0),
    todo: mainFiles.reduce((sum, file) => sum + file.todo, 0),
    emoji: mainFiles.reduce((sum, file) => sum + file.emoji, 0),
    largest: [...files].sort((left, right) => right.lines - left.lines).slice(0, 12)
      .map((file) => ({ rel: file.rel, lines: file.lines })),
    maxLines: Math.max(0, ...files.map((file) => file.lines)),
  }

  const stats = {
    files: files.length,
    lines: files.reduce((sum, file) => sum + file.lines, 0),
    mainFiles: mainFiles.length,
    testFiles: files.length - mainFiles.length,
    modules: {},
  }
  for (const file of files) {
    const module = stats.modules[file.module] ?? { files: 0, lines: 0 }
    module.files += 1
    module.lines += file.lines
    stats.modules[file.module] = module
  }

  const data = {
    root,
    scannedAt: new Date().toISOString(),
    durationMs: Date.now() - started,
    stats,
    tree,
    packages: [...packageStats.values()].sort((left, right) => right.lines - left.lines),
    edges,
    files: files.map((file) => ({
      id: file.rel,
      rel: file.rel,
      name: file.name,
      module: file.module,
      area: file.area,
      pkg: file.pkg,
      lines: file.lines,
      test: file.test,
      symbols: file.symbols,
      deps: file.deps,
    })),
    assets: assetNodes.map((node) => ({
      name: node.label,
      rows: node.rows,
      columns: node.columns,
      bytes: node.bytes,
      bom: node.bom,
      refs: node.refs,
    })),
    docs: docNodes.map((node) => ({ rel: node.path, title: node.label, lines: node.lines })),
    debt,
    tunables: readTunables(root),
    overview: overviewPath(root) !== null,
  }

  cache = { root, at: Date.now(), data }
  return data
}
