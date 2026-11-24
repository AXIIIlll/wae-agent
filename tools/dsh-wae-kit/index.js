/**
 * WAE kit · host half.
 *
 * Serves the map panel's data over `/dsh-wae-kit/api`: the scanned project
 * snapshot, build and device status, background Gradle/adb jobs, the static
 * flow overview, a read-only code preview, and the tunables write.
 */
import { existsSync, readFileSync, statSync } from 'node:fs'
import { extname, resolve, sep } from 'node:path'
import { createActions } from './lib/actions.js'
import { overviewPath, saveTunable, scanProject } from './lib/scan.js'

export const name = 'dsh-wae-kit'
export const inject = ['webServer']

const PREFIX = '/dsh-wae-kit'
const PREVIEW_EXTENSIONS = new Set(['.kt', '.kts', '.md', '.tsv', '.json', '.xml', '.yml', '.yaml', '.txt', '.gradle'])
const MAX_PREVIEW_BYTES = 2 * 1024 * 1024

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  })
}

function text(body, status = 200, contentType = 'text/plain; charset=utf-8') {
  return new Response(body, {
    status,
    headers: { 'content-type': contentType, 'cache-control': 'no-store' },
  })
}

export function apply(ctx, config = {}) {
  const root = resolve(config.projectRoot ?? process.cwd())
  const actions = createActions({
    root,
    javaHome: config.javaHome ?? process.env.JAVA_HOME ?? '',
    gradleUserHome: config.gradleUserHome ?? '',
    adbPath: config.adbPath ?? 'adb',
  })

  const handle = async (request) => {
    const url = new URL(request.url)
    const path = url.pathname.slice(PREFIX.length)
    if (request.method === 'GET' && path === '/api/health') {
      return json({ ok: true, root, scanned: existsSync(root) })
    }
    if (request.method === 'GET' && path === '/api/graph') {
      if (!existsSync(root)) return json({ error: `找不到專案根目錄：${root}` }, 404)
      return json(scanProject({ root, force: url.searchParams.has('refresh') }))
    }
    if (request.method === 'GET' && path === '/api/status') {
      return json(await actions.status())
    }
    if (request.method === 'GET' && path === '/api/job') {
      const job = actions.job(url.searchParams.get('id') ?? '')
      return job === null ? json({ error: 'unknown job' }, 404) : json(job)
    }
    if (request.method === 'GET' && path === '/api/file') {
      const rel = url.searchParams.get('path') ?? ''
      const absolute = resolve(root, rel)
      if (!absolute.startsWith(root + sep)) return text('forbidden', 403)
      if (!PREVIEW_EXTENSIONS.has(extname(absolute).toLowerCase())) return text('unsupported file type', 415)
      if (!existsSync(absolute) || !statSync(absolute).isFile()) return text('not found', 404)
      if (statSync(absolute).size > MAX_PREVIEW_BYTES) return text('file too large', 413)
      return text(readFileSync(absolute, 'utf8'))
    }
    if (request.method === 'GET' && path === '/api/overview.svg') {
      const file = overviewPath(root)
      if (file === null) return text('overview svg not found', 404)
      return text(readFileSync(file, 'utf8'), 200, 'image/svg+xml; charset=utf-8')
    }
    if (request.method === 'POST' && path === '/api/action') {
      let body
      try {
        body = await request.json()
      } catch {
        return json({ error: 'invalid json' }, 400)
      }
      try {
        return json(actions.start(String(body?.action ?? '')))
      } catch (error) {
        return json({ error: String(error?.message ?? error) }, 400)
      }
    }
    if (request.method === 'POST' && path === '/api/tunables') {
      let body
      try {
        body = await request.json()
      } catch {
        return json({ error: 'invalid json' }, 400)
      }
      try {
        return json({ ok: true, ...saveTunable(root, String(body?.key ?? ''), String(body?.value ?? '')) })
      } catch (error) {
        return json({ error: String(error?.message ?? error) }, 400)
      }
    }
    return json({ error: `unknown route: ${path}` }, 404)
  }

  ctx.effect(() => ctx.webServer.register({
    kind: 'prefix',
    path: PREFIX,
    resource: async (request) => {
      try {
        return await handle(request)
      } catch (error) {
        return json({ error: String(error?.message ?? error) }, 500)
      }
    },
  }), 'wae-kit: api routes')
}
