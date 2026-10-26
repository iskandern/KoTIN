#!/usr/bin/env node
// Сверка Compose- и DOM-версий интерфейса KoTIN по историям (docs/ui-sync-plan.md, этап 3).
//
//   node scripts/ui-sync/compare.mjs [--build] [--story имя[,имя…]] [--headed]
//
// Берёт готовые сборки app/build/dist/wasmJs/productionExecutable и webhtml/build/dist/wasmJs/productionExecutable
// (--build — сначала собрать их gradle), отдаёт обе с одного локального сервера без кэша, для каждой истории и размера
// окна открывает обе (?story=…&scene=0), ждёт window.kotinIdle(), сравнивает рамки window.kotinLayout() с допуском
// ±1 px и снимает экран. Отчёт — agent-out/ui-sync/index.html. Код выхода 1 — рамки расходятся или история не открылась.

import { createServer } from 'node:http'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { extname, join, normalize, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import pixelmatch from 'pixelmatch'
import { PNG } from 'pngjs'

const ROOT = resolve(fileURLToPath(import.meta.url), '../../..')
const BUILDS = {
  compose: join(ROOT, 'app/build/dist/wasmJs/productionExecutable'),
  dom: join(ROOT, 'webhtml/build/dist/wasmJs/productionExecutable'),
}
const OUT = join(ROOT, 'agent-out/ui-sync')
const TOLERANCE = 1
const VIEWPORTS = { desktop: { width: 1200, height: 800 }, phone: { width: 390, height: 844 } }
// имена историй и для каких окон — как Stories.all в app/src/commonMain/kotlin/dev/iskandern/kotin/Stories.kt
const STORIES = [
  ['empty'], ['loaded'], ['menu'], ['card', 'desktop'], ['card-expanded', 'desktop'], ['candidates'], ['candidates-list'],
  ['sheet', 'phone'], ['sheet-expanded', 'phone'], ['hint'], ['labels-notice'], ['load-error'],
]
const IDLE_TIMEOUT_MS = 90_000
// известные расхождения, где эталон спорный: в отчёте — отдельным разделом, рамки при этом не прячутся
const DISPUTED = [
  {
    title: 'Ширина текста',
    elements: 'mode-orbit, mode-map, camera-*, empty-open, hint-action, hint-text, selection-name, selection-layer, section-elements (меню)',
    text: 'Skia в Compose-версии ставит буквы шире браузера: обычный текст — на 0,2–0,3 px на букву, полужирный 13–14 px — ' +
      'на 0,6–0,9 px («Открыть файл» 109 против 100 px, «Карта» 47 против 42,5). Отсюда ширина кнопок по тексту и перенос ' +
      '«Прореживать подписи по масштабу» в меню (раздел «Элементы» ниже на 12 px). Похоже на целые шаги букв без субпиксельного ' +
      'положения (FontRasterizationSettings Skia), но не проверено. DOM не подгонялся: letter-spacing под каждый шрифт был бы ' +
      'подделкой. Варианты: включить субпиксельное положение текста в app (меняет вид app) или принять.',
  },
  {
    title: 'Высота строк в ячейках свойств карточки',
    elements: 'attr-row-*, attr-scroll, selection-card, sheet и всё, что под ними',
    text: 'В app у текста ячейки lineHeight = 17,5 sp (CELL_LINE, «та же сумма, что была у двух текстов»), но Skia рисует ' +
      'каждую строку по 19 px: строка из двух — 54 px вместо 51, из трёх — 73 вместо 68,5. DOM держит 17,5 px, как задумано ' +
      'в коде. Что эталон — нарисованное (тогда в DOM line-height 19 px) или задуманное (тогда править app), — решает владелец.',
  },
  {
    title: 'Тень бокового меню телефона',
    elements: 'drawer (только картинка)',
    text: 'В DOM тень меню — копии со сдвигом вправо, в app — кольца со сдвигом вниз; так сделано намеренно раньше (у краёв ' +
      'экрана кольца торчали углом в Safari). Рамки совпадают, отличается полоса тени у правого края.',
  },
]

const args = process.argv.slice(2)
const flag = (name) => args.includes(name)
const option = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : null }
const only = option('--story')?.split(',')

if (flag('--build')) {
  const env = { ...process.env, ...(process.env.JAVA_HOME_24 ? { JAVA_HOME: process.env.JAVA_HOME_24 } : {}) }
  execFileSync(join(ROOT, 'gradlew'), [':app:wasmJsBrowserDistribution', ':webhtml:wasmJsBrowserDistribution', '-q'], { cwd: ROOT, env, stdio: 'inherit' })
}
for (const [name, dir] of Object.entries(BUILDS)) {
  if (!existsSync(join(dir, 'index.html'))) {
    console.error(`нет сборки ${name}: ${dir} — соберите или запустите с --build`)
    process.exit(2)
  }
}

// ---------- локальный сервер: /compose/… и /dom/…, без кэша ----------
const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.wasm': 'application/wasm', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.ttf': 'font/ttf', '.xml': 'application/xml', '.webmanifest': 'application/manifest+json',
}
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://x')
  const [, build, ...rest] = url.pathname.split('/')
  const base = BUILDS[build]
  const path = base && normalize(join(base, decodeURIComponent(rest.join('/')) || 'index.html'))
  if (!path || !path.startsWith(base) || !existsSync(path) || !statSync(path).isFile()) {
    res.writeHead(404, { 'Cache-Control': 'no-store' })
    res.end('not found')
    return
  }
  res.writeHead(200, { 'Content-Type': TYPES[extname(path)] ?? 'application/octet-stream', 'Cache-Control': 'no-store' })
  res.end(readFileSync(path))
})
await new Promise((ok) => server.listen(0, '127.0.0.1', ok))
const origin = `http://127.0.0.1:${server.address().port}`

// ---------- снимки ----------
rmSync(OUT, { recursive: true, force: true })
mkdirSync(join(OUT, 'img'), { recursive: true })

const browser = await chromium.launch({
  headless: !flag('--headed'),
  args: ['--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--font-render-hinting=none'],
})

async function capture(build, story, viewport, file) {
  const context = await browser.newContext({ viewport, deviceScaleFactor: 1, locale: 'ru-RU' })
  const page = await context.newPage()
  const log = []
  page.on('console', (m) => { if (m.type() === 'error' || m.type() === 'warning' || /KoTIN/.test(m.text())) log.push(`${m.type()}: ${m.text()}`) })
  page.on('pageerror', (e) => log.push(`pageerror: ${e.message}`))
  const started = Date.now()
  let error = null
  let layout = {}
  try {
    await page.goto(`${origin}/${build}/index.html?story=${story}&scene=0`)
    await page.waitForFunction(() => typeof window.kotinIdle === 'function' && window.kotinIdle(), null, { timeout: IDLE_TIMEOUT_MS, polling: 100 })
    layout = await page.evaluate(() => window.kotinLayout())
  } catch (e) {
    error = e.message.split('\n')[0]
  }
  await page.screenshot({ path: join(OUT, 'img', file) }).catch(() => {})
  await context.close()
  return { layout, error, log, millis: Date.now() - started }
}

function compareLayouts(compose, dom) {
  const names = [...new Set([...Object.keys(compose), ...Object.keys(dom)])].sort()
  return names.map((name) => {
    const c = compose[name]
    const d = dom[name]
    const cv = c?.visible ?? false
    const dv = d?.visible ?? false
    let delta = null
    if (c && d) delta = Math.max(...['x', 'y', 'w', 'h'].map((k) => Math.abs(c[k] - d[k])))
    // элемент, которого не видно ни там, ни там, не расходится
    const ok = (!cv && !dv) || (cv === dv && delta !== null && delta <= TOLERANCE)
    return { name, compose: c, dom: d, delta, ok }
  })
}

function pixelDiff(a, b, out) {
  const pa = PNG.sync.read(readFileSync(join(OUT, 'img', a)))
  const pb = PNG.sync.read(readFileSync(join(OUT, 'img', b)))
  if (pa.width !== pb.width || pa.height !== pb.height) return null
  const diff = new PNG({ width: pa.width, height: pa.height })
  // порог 0,2: сглаживание текста Skia и браузера расходится в оттенках, а не в местах
  const count = pixelmatch(pa.data, pb.data, diff.data, pa.width, pa.height, { threshold: 0.2, includeAA: false, alpha: 0.3 })
  writeFileSync(join(OUT, 'img', out), PNG.sync.write(diff))
  return count / (pa.width * pa.height)
}

const results = []
for (const [story, only1] of STORIES) {
  if (only && !only.includes(story)) continue
  for (const [vp, size] of Object.entries(VIEWPORTS)) {
    if (only1 && only1 !== vp) continue
    const id = `${story}-${vp}`
    const compose = await capture('compose', story, size, `${id}-compose.png`)
    const dom = await capture('dom', story, size, `${id}-dom.png`)
    const rows = compareLayouts(compose.layout, dom.layout)
    const diff = existsSync(join(OUT, 'img', `${id}-compose.png`)) && existsSync(join(OUT, 'img', `${id}-dom.png`))
      ? pixelDiff(`${id}-compose.png`, `${id}-dom.png`, `${id}-diff.png`) : null
    const bad = rows.filter((r) => !r.ok).length
    const failed = compose.error || dom.error
    results.push({ story, vp, id, compose, dom, rows, diff, bad, failed })
    const status = failed ? 'НЕ ОТКРЫЛАСЬ' : bad ? `расходится ${bad}` : 'совпадает'
    console.log(`${id.padEnd(28)} ${status.padEnd(16)} пикселей ${diff == null ? '—' : (diff * 100).toFixed(2) + ' %'}` +
      `  (${compose.millis} мс / ${dom.millis} мс)${failed ? '  ' + (compose.error ?? '') + ' ' + (dom.error ?? '') : ''}`)
  }
}
await browser.close()
server.close()

// ---------- отчёт ----------
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c])
const rect = (r) => r ? `${r.x.toFixed(1)}, ${r.y.toFixed(1)} · ${r.w.toFixed(1)} × ${r.h.toFixed(1)}${r.visible ? '' : ' <i>скрыт</i>'}` : '<i>нет</i>'
const sections = results.map((r) => `
<section id="${r.id}">
  <h2>${esc(r.story)} <small>${r.vp} ${VIEWPORTS[r.vp].width}×${VIEWPORTS[r.vp].height}</small>
    <span class="tag ${r.failed ? 'bad' : r.bad ? 'bad' : 'ok'}">${r.failed ? 'не открылась' : r.bad ? `рамок расходится: ${r.bad}` : 'рамки совпадают'}</span>
    ${r.diff == null ? '' : `<span class="tag">пикселей отличается: ${(r.diff * 100).toFixed(2)} %</span>`}</h2>
  ${r.failed ? `<p class="err">${esc(r.compose.error ?? '')} ${esc(r.dom.error ?? '')}</p>` : ''}
  <div class="shots ${r.vp}">
    <figure><figcaption>Compose (эталон)</figcaption><img src="img/${r.id}-compose.png" loading="lazy"></figure>
    <figure><figcaption>DOM</figcaption><img src="img/${r.id}-dom.png" loading="lazy"></figure>
    <figure><figcaption>Разница</figcaption>${r.diff == null ? '<p>—</p>' : `<img src="img/${r.id}-diff.png" loading="lazy">`}</figure>
  </div>
  <table><thead><tr><th>элемент</th><th>Compose</th><th>DOM</th><th>Δ, px</th></tr></thead><tbody>
  ${r.rows.map((row) => `<tr class="${row.ok ? '' : 'bad'}"><td>${esc(row.name)}</td><td>${rect(row.compose)}</td><td>${rect(row.dom)}</td><td>${row.delta == null ? '—' : row.delta.toFixed(1)}</td></tr>`).join('\n  ')}
  </tbody></table>
  ${[...r.compose.log.map((l) => 'compose ' + l), ...r.dom.log.map((l) => 'dom ' + l)].length ? `<details><summary>консоль</summary><pre>${esc([...r.compose.log.map((l) => 'compose ' + l), ...r.dom.log.map((l) => 'dom ' + l)].join('\n'))}</pre></details>` : ''}
</section>`).join('\n')

const total = results.reduce((n, r) => n + r.bad, 0)
const failedCount = results.filter((r) => r.failed).length
writeFileSync(join(OUT, 'index.html'), `<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Сверка Compose и DOM</title>
<style>
  :root { --bg: #f6f7f9; --fg: #15202b; --muted: #56636f; --bad: #c4312a; --bad-bg: #fde8e7; --ok: #23894c; --line: #d3d9df; }
  body { font: 14px/1.4 system-ui, sans-serif; background: var(--bg); color: var(--fg); margin: 0; padding: 16px; }
  h1 { font-size: 20px; } h2 { font-size: 16px; margin: 0 0 8px; } small { color: var(--muted); font-weight: 400; }
  section { background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 16px; margin: 16px 0; }
  .tag { font-size: 12px; font-weight: 500; padding: 2px 8px; border-radius: 8px; background: var(--bg); margin-left: 8px; }
  .tag.bad { background: var(--bad-bg); color: var(--bad); } .tag.ok { color: var(--ok); }
  .shots { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; } .shots.phone { grid-template-columns: repeat(3, minmax(0, 260px)); }
  figure { margin: 0; } figcaption { color: var(--muted); font-size: 12px; } img { width: 100%; border: 1px solid var(--line); }
  table { border-collapse: collapse; margin-top: 12px; font: 12px/1.3 ui-monospace, monospace; }
  td, th { border-bottom: 1px solid var(--line); padding: 3px 10px 3px 0; text-align: left; }
  tr.bad td { color: var(--bad); background: var(--bad-bg); } .err { color: var(--bad); }
  nav a { margin-right: 10px; } pre { white-space: pre-wrap; font-size: 11px; }
</style></head><body>
<h1>Сверка Compose ↔ DOM</h1>
<p>Историй: ${results.length}; не открылось: ${failedCount}; расходящихся рамок (допуск ±${TOLERANCE} px): ${total}. Снято ${new Date().toISOString()}.</p>
<h2>Спорное — ждёт решения владельца</h2>
${DISPUTED.map((d) => `<section><h2>${esc(d.title)} <small>${esc(d.elements)}</small></h2><p>${esc(d.text)}</p></section>`).join('\n')}
<nav>${results.map((r) => `<a href="#${r.id}" style="color:${r.failed || r.bad ? 'var(--bad)' : 'var(--ok)'}">${r.id}</a>`).join(' ')}</nav>
${sections}
</body></html>
`)
writeFileSync(join(OUT, 'results.json'), JSON.stringify(results.map(({ id, rows, diff, failed }) => ({ id, failed, diff, rows })), null, 1))
console.log(`\nотчёт: ${join(OUT, 'index.html')}\nне открылось: ${failedCount}, расходящихся рамок: ${total}`)
process.exit(failedCount || total ? 1 : 0)
