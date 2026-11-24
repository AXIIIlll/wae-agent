/**
 * WAE project actions: build/test status, device state, and background jobs
 * that run Gradle or adb for the map panel's build tab.
 *
 * Nothing here writes project files; `scan.js` owns the one write (a tunable).
 */
import { execFile, spawn } from 'node:child_process'
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

const ACCESSIBILITY_COMPONENT = 'com.wae.agent/com.wae.agent.core.WaeVisionAccessibilityService'
const TEST_RESULTS = 'app/build/test-results/testFullDebugUnitTest'
const APKS = {
  full: 'app/build/outputs/apk/full/debug/app-full-debug.apk',
  lite: 'app/build/outputs/apk/lite/debug/app-lite-debug.apk',
}

const jobs = new Map()
let sequence = 0

function attr(attributes, name) {
  const match = new RegExp(`${name}="([^"]*)"`).exec(attributes)
  return match ? match[1] : null
}

function run(command, args, options) {
  return new Promise((resolve) => {
    const child = execFile(command, args, {
      cwd: options.cwd,
      timeout: options.timeout ?? 8000,
      windowsHide: true,
      maxBuffer: 4 * 1024 * 1024,
      env: { ...process.env, ...options.env },
    }, (error, stdout, stderr) => {
      resolve({ code: error ? (typeof error.code === 'number' ? error.code : 1) : 0, stdout: String(stdout ?? ''), stderr: String(stderr ?? '') })
    })
    child.on('error', () => resolve({ code: 1, stdout: '', stderr: '' }))
  })
}

function readTests(root) {
  const dir = join(root, TEST_RESULTS)
  if (!existsSync(dir)) return null
  const summary = { tests: 0, failures: 0, errors: 0, skipped: 0, suites: 0, failed: [], mtime: null }
  let newest = 0
  for (const name of readdirSync(dir)) {
    if (!name.endsWith('.xml')) continue
    const path = join(dir, name)
    let text = ''
    try {
      text = readFileSync(path, 'utf8').slice(0, 400000)
    } catch {
      continue
    }
    const suite = /<testsuite\b([^>]*)>/.exec(text)
    if (!suite) continue
    summary.suites += 1
    summary.tests += Number(attr(suite[1], 'tests') ?? 0)
    summary.failures += Number(attr(suite[1], 'failures') ?? 0)
    summary.errors += Number(attr(suite[1], 'errors') ?? 0)
    summary.skipped += Number(attr(suite[1], 'skipped') ?? 0)
    const stamp = statSync(path).mtimeMs
    if (stamp > newest) newest = stamp
    const failureRe = /<testcase\b([^>]*?)(?:\/>|>\s*<(failure|error)\b)/g
    let match
    while ((match = failureRe.exec(text)) !== null && summary.failed.length < 12) {
      if (match[2] === undefined) continue
      summary.failed.push(attr(match[1], 'name') ?? '(unknown)')
    }
  }
  summary.mtime = newest === 0 ? null : new Date(newest).toISOString()
  return summary
}

function readApk(root, flavor) {
  const path = join(root, APKS[flavor])
  if (!existsSync(path)) return null
  const stat = statSync(path)
  return { path: APKS[flavor], bytes: stat.size, mtime: new Date(stat.mtimeMs).toISOString() }
}

async function readDevice(config) {
  if (!existsSync(config.adbPath)) return { adb: false, devices: [], serial: null, accessibility: null }
  const listed = await run(config.adbPath, ['devices', '-l'], { timeout: 8000 })
  const devices = []
  for (const line of listed.stdout.split(/\r?\n/).slice(1)) {
    const match = /^(\S+)\s+(\S+)(?:\s+(.*))?$/.exec(line.trim())
    if (!match) continue
    if (match[1] === '*') continue
    devices.push({ serial: match[1], state: match[2], detail: match[3] ?? '' })
  }
  const serial = devices.find((device) => device.state === 'device')?.serial ?? null
  let accessibility = null
  if (serial !== null) {
    const [services, enabled] = await Promise.all([
      run(config.adbPath, ['-s', serial, 'shell', 'settings', 'get', 'secure', 'enabled_accessibility_services'], { timeout: 8000 }),
      run(config.adbPath, ['-s', serial, 'shell', 'settings', 'get', 'secure', 'accessibility_enabled'], { timeout: 8000 }),
    ])
    accessibility = {
      services: services.stdout.trim(),
      enabled: enabled.stdout.trim() === '1',
      bound: services.stdout.includes(ACCESSIBILITY_COMPONENT),
    }
  }
  return { adb: true, devices, serial, accessibility }
}

function trimJob(job) {
  if (job.lines.length > 4000) job.lines.splice(0, job.lines.length - 4000)
}

function appendChunk(job, chunk) {
  const text = String(chunk)
  for (const line of text.split(/\r?\n/)) {
    if (line !== '') job.lines.push(line)
  }
  trimJob(job)
}

function spawnStep(job, step) {
  return new Promise((resolve) => {
    const child = spawn(step.command, step.args, {
      cwd: step.cwd,
      windowsHide: true,
      env: { ...process.env, ...step.env },
    })
    child.stdout.on('data', (chunk) => appendChunk(job, chunk))
    child.stderr.on('data', (chunk) => appendChunk(job, chunk))
    child.on('error', (error) => {
      job.lines.push(String(error.message ?? error))
      resolve(1)
    })
    child.on('close', (code) => resolve(code ?? 1))
  })
}

function publicJob(job) {
  return {
    id: job.id,
    kind: job.kind,
    label: job.label,
    status: job.status,
    exitCode: job.exitCode,
    startedAt: job.startedAt,
    finishedAt: job.finishedAt,
    lines: job.lines,
  }
}

/** Start a background job unless one of the same kind already runs. */
function startJob(kind, label, steps) {
  for (const job of jobs.values()) {
    if (job.status === 'running' && job.kind === kind) return publicJob(job)
  }
  sequence += 1
  const job = {
    id: `${kind}-${Date.now()}-${sequence}`,
    kind,
    label,
    status: 'running',
    exitCode: null,
    startedAt: new Date().toISOString(),
    finishedAt: null,
    lines: [],
  }
  jobs.set(job.id, job)
  void (async () => {
    try {
      for (const step of steps) {
        job.lines.push(`$ ${step.label}`)
        const code = await spawnStep(job, step)
        if (code !== 0) {
          job.status = 'failed'
          job.exitCode = code
          return
        }
      }
      job.status = 'done'
      job.exitCode = 0
    } catch (error) {
      job.lines.push(String(error?.message ?? error))
      job.status = 'failed'
      job.exitCode = 1
    } finally {
      job.finishedAt = new Date().toISOString()
      for (const [id, old] of jobs) {
        if (jobs.size <= 8) break
        if (old.status !== 'running') jobs.delete(id)
      }
    }
  })()
  return publicJob(job)
}

export function createActions(config) {
  const root = config.root

  const status = async () => ({
    root,
    fetchedAt: new Date().toISOString(),
    apk: { full: readApk(root, 'full'), lite: readApk(root, 'lite') },
    tests: readTests(root),
    device: await readDevice(config),
    jobs: [...jobs.values()].map(publicJob).slice(-5),
  })

  const start = (action) => {
    const gradle = join(root, 'gradlew.bat')
    const gradleStep = (task, label) => ({
      label,
      command: 'cmd.exe',
      args: ['/d', '/s', '/c', `"${gradle}" ${task} --console=plain --no-daemon`],
      cwd: root,
      env: { JAVA_HOME: config.javaHome, GRADLE_USER_HOME: config.gradleUserHome },
    })
    if (action === 'test') {
      return startJob('test', '單元測試（full debug）', [gradleStep(':app:testFullDebugUnitTest', ':app:testFullDebugUnitTest')])
    }
    if (action === 'build') {
      return startJob('build', '建置 full debug APK', [gradleStep(':app:assembleFullDebug', ':app:assembleFullDebug')])
    }
    if (action === 'install') {
      const apk = join(root, APKS.full)
      if (!existsSync(apk)) throw new Error(`找不到 APK：${APKS.full}`)
      const adb = config.adbPath
      return startJob('install', '安裝並重綁無障礙', [
        {
          label: `adb install -r ${APKS.full}`,
          command: adb,
          args: ['install', '-r', apk],
          cwd: root,
          env: {},
        },
        {
          label: '重新綁定無障礙服務',
          command: adb,
          args: ['shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ACCESSIBILITY_COMPONENT],
          cwd: root,
          env: {},
        },
        {
          label: '開啟無障礙總開關',
          command: adb,
          args: ['shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1'],
          cwd: root,
          env: {},
        },
      ])
    }
    if (action === 'rebind') {
      const adb = config.adbPath
      return startJob('rebind', '重綁無障礙服務', [
        {
          label: '重新綁定無障礙服務',
          command: adb,
          args: ['shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ACCESSIBILITY_COMPONENT],
          cwd: root,
          env: {},
        },
        {
          label: '開啟無障礙總開關',
          command: adb,
          args: ['shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1'],
          cwd: root,
          env: {},
        },
      ])
    }
    throw new Error(`未知動作：${action}`)
  }

  const job = (id) => {
    const found = jobs.get(id)
    return found ? publicJob(found) : null
  }

  return { status, start, job }
}

export { ACCESSIBILITY_COMPONENT }
