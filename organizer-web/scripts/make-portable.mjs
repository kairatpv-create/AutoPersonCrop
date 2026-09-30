import fs from 'node:fs'
import path from 'node:path'

const dist = path.resolve('dist')
const indexPath = path.join(dist, 'index.html')
let html = fs.readFileSync(indexPath, 'utf8')

const scriptMatch = html.match(/<script[^>]*type="module"[^>]*src="([^"]+)"[^>]*><\/script>/)
if (!scriptMatch) throw new Error('Built module script was not found in dist/index.html')
const scriptPath = path.join(dist, scriptMatch[1].replace(/^\.\//, ''))
let js = fs.readFileSync(scriptPath, 'utf8').replace(/<\/script/gi, '<\\/script')
html = html.replace(scriptMatch[0], `<script type="module">${js}</script>`)

const cssMatch = html.match(/<link[^>]*rel="stylesheet"[^>]*href="([^"]+)"[^>]*>/)
if (cssMatch) {
  const cssPath = path.join(dist, cssMatch[1].replace(/^\.\//, ''))
  const css = fs.readFileSync(cssPath, 'utf8').replace(/<\/style/gi, '<\\/style')
  html = html.replace(cssMatch[0], `<style>${css}</style>`)
}

html = html.replace('</head>', '<meta name="organizer-portable" content="true"/></head>')
fs.writeFileSync(indexPath, html, 'utf8')
fs.rmSync(path.join(dist, 'assets'), { recursive: true, force: true })

console.log(`Portable Organizer Pro Web written: ${indexPath} (${fs.statSync(indexPath).size} bytes)`)
