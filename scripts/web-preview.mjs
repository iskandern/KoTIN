// Заставка web: снимает стартовый экран собранной web-версии на русском и английском (телефон 390×844 и
// планшет 1024×768), вырезает панель слева сверху и карточку по центру, размывает и вписывает в index.html
// строкой SHOTS (data: URL — без запроса). Фон сцены в вырезках прозрачный: по ним же index.html
// ограничивает блик шиммера. Перегенерировать, когда меняется вид стартового экрана.
//   ./gradlew :app:wasmJsBrowserDistribution
//   PLAYWRIGHT_MODULE=<…>/node_modules/playwright node scripts/web-preview.mjs
// Нужен ImageMagick (magick).
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { execFileSync } from 'node:child_process';

const { chromium } = await import(path.join(process.env.PLAYWRIGHT_MODULE ?? 'playwright', 'index.mjs'));
const dist = 'app/build/dist/wasmJs/productionExecutable';
const html = 'app/src/wasmJsMain/resources/index.html';
const types = { '.html': 'text/html', '.js': 'text/javascript', '.wasm': 'application/wasm' };
const SCENE = '#E3E7EB'; // KotinColors.Scene
const SHADOW = 16; // запас вокруг карточки на тень, px; index.html считает размеры вырезок с ним

const server = http.createServer((req, res) => {
  let p = new URL(req.url, 'http://x').pathname;
  if (p.endsWith('/')) p += 'index.html';
  const file = path.join(dist, decodeURIComponent(p));
  if (!fs.existsSync(file)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'Content-Type': types[path.extname(file)] ?? 'application/octet-stream' });
  res.end(fs.readFileSync(file));
}).listen(0);
const url = `http://localhost:${server.address().port}/`;

// карточка — самая большая белая область снимка
function cardBox(png) {
  const report = execFileSync('magick', [png, '-fill', 'black', '+opaque', 'white', '-define', 'connected-components:verbose=true',
    '-define', 'connected-components:area-threshold=2000', '-connected-components', '8', 'null:']).toString();
  const boxes = [...report.matchAll(/(\d+)x(\d+)\+(\d+)\+(\d+) \S+ (\d+) srgb\(255,255,255\)/g)]
    .map(m => ({ w: +m[1], h: +m[2], x: +m[3], y: +m[4], area: +m[5] }));
  return boxes.sort((a, b) => b.area - a.area)[0];
}

function crop(png, geometry) {
  const file = png.replace(/\.png$/, `-${geometry}.webp`);
  // размытие и половинный размер: заставка растягивается вдвое, мелочь всё равно не видна, файл ≈ 1 КБ
  execFileSync('magick', [png, '-crop', geometry, '+repage', '-alpha', 'set', '-fuzz', '1%', '-transparent', SCENE,
    '-channel', 'RGBA', '-blur', '0x4', '-resize', '50%', '-quality', '70', '-define', 'webp:method=6', file]);
  return `data:image/webp;base64,${fs.readFileSync(file).toString('base64')}`;
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kotin-preview-'));
const shots = { ru: {}, en: {} };
const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
for (const [lang, locale] of [['ru', 'ru-RU'], ['en', 'en-US']]) {
  for (const [device, width, height, top] of [['phone', 390, 844, 80], ['tablet', 1024, 768, 372]]) {
    const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1, locale });
    await page.goto(url);
    await page.waitForFunction(() => !document.getElementById('preview'), null, { timeout: 60000 });
    await page.waitForTimeout(500);
    const png = path.join(tmp, `${lang}-${device}.png`);
    await page.screenshot({ path: png });
    await page.close();
    const card = cardBox(png);
    shots[lang][`${device}-top`] = crop(png, `${top}x80+0+0`);
    shots[lang][`${device}-card`] = crop(png, `${card.w + 2 * SHADOW}x${card.h + 2 * SHADOW}+${card.x - SHADOW}+${card.y - SHADOW}`);
    console.log(`${lang} ${device}: карточка ${card.w}×${card.h}`);
  }
}
await browser.close();
server.close();
const source = fs.readFileSync(html, 'utf8');
const updated = source.replace(/const SHOTS = .*;/, () => `const SHOTS = ${JSON.stringify(shots)};`);
if (updated === source) throw new Error(`в ${html} нет строки const SHOTS = …;`);
fs.writeFileSync(html, updated);
