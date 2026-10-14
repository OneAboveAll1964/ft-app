import puppeteer from 'puppeteer-core'
import { spawn } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const args = Object.fromEntries(process.argv.slice(2).map(a => a.replace(/^--/, '').split('=')))
const fps = Number(args.fps || 60)
const out = resolve(here, args.out || 'out/ft-tour.mp4')
const chrome = args.chrome || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

const browser = await puppeteer.launch({
  executablePath: chrome,
  headless: true,
  args: ['--hide-scrollbars', '--force-device-scale-factor=1', '--allow-file-access-from-files', '--font-render-hinting=none'],
})
const page = await browser.newPage()
await page.setViewport({ width: 1920, height: 1080, deviceScaleFactor: 1 })
await page.goto(pathToFileURL(resolve(here, 'index.html')).href, { waitUntil: 'load' })
await page.evaluate(() => window.ready)
const duration = await page.evaluate(() => window.DURATION)

if (args.stills) {
  const dir = resolve(here, args.dir || 'out/stills')
  mkdirSync(dir, { recursive: true })
  for (const t of args.stills.split(',').map(Number)) {
    await page.evaluate(x => window.render(x), t)
    await page.screenshot({ path: `${dir}/t${t.toFixed(2).padStart(6, '0')}.png`, type: 'png' })
  }
  await browser.close()
  process.exit(0)
}

mkdirSync(dirname(out), { recursive: true })
const from = Number(args.from || 0)
const to = Number(args.to || duration)
const ff = spawn('ffmpeg', [
  '-y', '-loglevel', 'error',
  '-f', 'image2pipe', '-framerate', String(fps), '-c:v', 'png', '-i', '-',
  ...(args.audio ? ['-i', resolve(here, args.audio), '-af', 'apad', '-c:a', 'aac', '-b:a', '192k', '-shortest'] : []),
  '-c:v', 'libx264', '-preset', 'slow', '-crf', '18', '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
  out,
], { stdio: ['pipe', 'inherit', 'inherit'] })

const frames = Math.round((to - from) * fps)
const started = Date.now()
for (let i = 0; i < frames; i++) {
  const t = from + i / fps
  await page.evaluate(x => window.render(x), t)
  const png = await page.screenshot({ type: 'png', optimizeForSpeed: true })
  if (!ff.stdin.write(png)) await new Promise(r => ff.stdin.once('drain', r))
  if (i % (fps * 5) === 0) process.stdout.write(`\r${(t).toFixed(1)}s of ${to}s, ${((Date.now() - started) / 1000).toFixed(0)}s elapsed   `)
}
ff.stdin.end()
await new Promise(r => ff.on('close', r))
await browser.close()
console.log(`\nwrote ${out}`)
