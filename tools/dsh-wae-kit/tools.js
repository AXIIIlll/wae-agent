/**
 * WAE kit · agent tools.
 *
 * `wae_map` answers project-shape questions from the same scanner the panel
 * uses. It is read-only and mounted inside an agent preset, not globally.
 */
import { scanProject } from './lib/scan.js'

export const name = 'dsh-wae-kit/tools'
export const inject = ['tools']

const DEFAULT_ROOT = 'E:\\WAE\\NewAndroidApp'

function summary(data) {
  const lines = [`WAE 專案：${data.stats.files} 檔 / ${data.stats.lines} 行（掃描 ${data.durationMs} ms）`]
  for (const [module, stats] of Object.entries(data.stats.modules)) {
    lines.push(`- ${module}：${stats.files} 檔 / ${stats.lines} 行`)
  }
  lines.push(`- 資料表 ${data.assets.length} 個、文件 ${data.docs.length} 份`)
  lines.push('', '最大套件（依行數）：')
  data.packages.slice(0, 10).forEach((pkg, index) => {
    lines.push(`${index + 1}. ${pkg.name} — ${pkg.lines} 行 / ${pkg.files} 檔 / 測試 ${pkg.tests}`)
  })
  lines.push('', `技術債：runCatching ${data.debt.runCatching}、TODO ${data.debt.todo}、emoji ${data.debt.emoji}、最大檔 ${data.debt.maxLines} 行`)
  return lines.join('\n')
}

function formatPackage(data, query) {
  const matches = data.packages.filter((pkg) => pkg.name.toLowerCase().includes(query.toLowerCase())).slice(0, 5)
  if (matches.length === 0) return `找不到套件：${query}`
  const lines = []
  for (const pkg of matches) {
    const files = data.files.filter((file) => file.pkg === pkg.name)
    const outbound = data.edges.filter((edge) => edge.from === pkg.name)
    const inbound = data.edges.filter((edge) => edge.to === pkg.name)
    lines.push(`## ${pkg.name}（${pkg.files} 檔 / ${pkg.lines} 行 / 測試 ${pkg.tests}）`)
    lines.push(`依賴：${outbound.length === 0 ? '—' : outbound.map((edge) => `${edge.to}(${edge.count})`).join('、')}`)
    lines.push(`被依賴：${inbound.length === 0 ? '—' : inbound.map((edge) => `${edge.from}(${edge.count})`).join('、')}`)
    lines.push('檔案：')
    for (const file of files.slice(0, 25)) lines.push(`- ${file.rel}（${file.lines} 行）`)
    if (files.length > 25) lines.push(`- …其餘 ${files.length - 25} 檔`)
    lines.push('')
  }
  return lines.join('\n')
}

function formatFiles(data, query) {
  const matches = data.files.filter((file) => file.rel.toLowerCase().includes(query.toLowerCase())
    || file.symbols.some((symbol) => symbol.name.toLowerCase().includes(query.toLowerCase()))).slice(0, 30)
  if (matches.length === 0) return `找不到檔案或宣告：${query}`
  return matches.map((file) => `- ${file.rel}（${file.lines} 行，${file.pkg}）`).join('\n')
}

function formatDatasets(data) {
  const lines = [`TSV 資料表 ${data.assets.length} 個：`]
  const sorted = [...data.assets].sort((left, right) => right.rows - left.rows)
  for (const asset of sorted) {
    const refs = asset.refs.length
    lines.push(`- ${asset.name}：${asset.rows} 列 × ${asset.columns} 欄${asset.bom ? '（有 BOM！）' : ''}，被 ${refs} 個 Kotlin 檔引用`)
  }
  return lines.join('\n')
}

function formatTunables(data) {
  if (data.tunables.length === 0) return 'ui_tunables.tsv 沒有可讀的資料列。'
  return [`ui_tunables.tsv（${data.tunables.length} 項）：`]
    .concat(data.tunables.map((row) => `- ${row.key} = ${row.value}${row.description ? ` — ${row.description}` : ''}`))
    .join('\n')
}

function formatDebt(data) {
  return [
    `runCatching ${data.debt.runCatching}、TODO ${data.debt.todo}、emoji ${data.debt.emoji}、最大檔 ${data.debt.maxLines} 行`,
    '',
    '最大的檔案：',
    ...data.debt.largest.map((file) => `- ${file.rel}（${file.lines} 行）`),
  ].join('\n')
}

export function apply(ctx, config = {}) {
  const root = config.projectRoot ?? process.env.DSH_WAE_PROJECT_ROOT ?? DEFAULT_ROOT
  ctx.effect(() => ctx.tools.register({
    name: 'wae_map',
    description: 'Query the WAE Android project map: module sizes, packages, package dependencies, files, tests, TSV datasets, tunables and technical-debt metrics. '
      + 'Use it for "where does X live", "what depends on Y", "which file is largest", "what tunables exist". Read-only; the answer is text.',
    parameters: {
      type: 'object',
      properties: {
        view: {
          type: 'string',
          enum: ['summary', 'package', 'files', 'datasets', 'tunables', 'debt'],
          description: 'Which slice to return. Defaults to summary.',
        },
        query: {
          type: 'string',
          description: 'Package name, file path, or symbol fragment. Required for view=package and view=files.',
        },
      },
      additionalProperties: false,
    },
    output: {
      schema: { type: 'string' },
      render(_args, value) {
        return [{ type: 'text', text: value }]
      },
    },
    async execute(args) {
      const data = scanProject({ root })
      const view = args?.view ?? 'summary'
      const query = (args?.query ?? '').trim()
      if (view === 'package') {
        if (query === '') throw new Error('view=package 需要 query（套件名稱片段）')
        return formatPackage(data, query)
      }
      if (view === 'files') {
        if (query === '') throw new Error('view=files 需要 query（檔名或宣告片段）')
        return formatFiles(data, query)
      }
      if (view === 'datasets') return formatDatasets(data)
      if (view === 'tunables') return formatTunables(data)
      if (view === 'debt') return formatDebt(data)
      return summary(data)
    },
  }), 'wae-kit: wae_map tool')
}
