import fs from 'node:fs'
import path from 'node:path'

const dist = path.resolve('dist')
const indexPath = path.join(dist, 'index.html')
let html = fs.readFileSync(indexPath, 'utf8')

const scriptMatch = html.match(/<script[^>]*type="module"[^>]*src="([^"]+)"[^>]*><\/script>/)
if (!scriptMatch) throw new Error('Built module script was not found in dist/index.html')
const scriptPath = path.join(dist, scriptMatch[1].replace(/^\.\//, ''))
const js = fs.readFileSync(scriptPath, 'utf8').replace(/<\/script/gi, '<\\/script')

// IMPORTANT: use a replacer function. Passing the minified bundle directly as the
// replacement string makes JavaScript sequences such as $&, $` and $' special to
// String.replace(), which corrupts the generated HTML and can expose raw JS as text.
html = html.replace(scriptMatch[0], () => `<script>${js}</script>`)

const cssMatch = html.match(/<link[^>]*rel="stylesheet"[^>]*href="([^"]+)"[^>]*>/)
if (cssMatch) {
  const cssPath = path.join(dist, cssMatch[1].replace(/^\.\//, ''))
  const css = fs.readFileSync(cssPath, 'utf8').replace(/<\/style/gi, '<\\/style')
  html = html.replace(cssMatch[0], () => `<style>${css}</style>`)
}

html = html.replace('</head>', '<meta name="organizer-portable" content="true"/></head>')
fs.writeFileSync(indexPath, html, 'utf8')
fs.rmSync(path.join(dist, 'assets'), { recursive: true, force: true })

// Build gates for a real one-file, double-clickable page.
const finalHtml = fs.readFileSync(indexPath, 'utf8')
const scriptOpenCount = (finalHtml.match(/<script(?:\s|>)/gi) || []).length
const scriptCloseCount = (finalHtml.match(/<\/script>/gi) || []).length
const styleOpenCount = (finalHtml.match(/<style(?:\s|>)/gi) || []).length
const styleCloseCount = (finalHtml.match(/<\/style>/gi) || []).length
if (scriptOpenCount !== 1 || scriptCloseCount !== 1) {
  throw new Error(`Portable HTML has invalid script boundaries: open=${scriptOpenCount}, close=${scriptCloseCount}`)
}
if (styleOpenCount !== 1 || styleCloseCount !== 1) {
  throw new Error(`Portable HTML has invalid style boundaries: open=${styleOpenCount}, close=${styleCloseCount}`)
}
if (/\bsrc=["'][^"']+\.js/i.test(finalHtml)) throw new Error('Portable HTML still references an external JS file')
if (/\bhref=["'][^"']+\.css/i.test(finalHtml)) throw new Error('Portable HTML still references an external CSS file')
if (!finalHtml.includes('<div id="root"></div>')) throw new Error('React root element missing from portable HTML')
if (!finalHtml.includes('name="organizer-portable" content="true"')) throw new Error('Portable marker missing')

console.log(`Portable Organizer Pro Web written: ${indexPath} (${fs.statSync(indexPath).size} bytes)`)
console.log(`Validated one-file HTML: scripts=${scriptOpenCount}/${scriptCloseCount}, styles=${styleOpenCount}/${styleCloseCount}`)
