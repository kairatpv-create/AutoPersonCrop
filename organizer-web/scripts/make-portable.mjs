import fs from 'node:fs'
import path from 'node:path'

const dist = path.resolve('dist')
const indexPath = path.join(dist, 'index.html')
let html = fs.readFileSync(indexPath, 'utf8')

const scriptMatch = html.match(/<script[^>]*type="module"[^>]*src="([^"]+)"[^>]*><\/script>/)
if (!scriptMatch) throw new Error('Built module script was not found in dist/index.html')
const scriptPath = path.join(dist, scriptMatch[1].replace(/^\.\//, ''))
const js = fs.readFileSync(scriptPath, 'utf8').replace(/<\/script/gi, '<\\/script')

// Use a replacer function. A minified bundle can contain $&, $` or $', which
// String.replace interprets specially if the bundle is passed as a replacement string.
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

// Validate HTML boundaries, ignoring harmless literal "<script" strings that may exist
// inside React/Firebase's minified JavaScript bundle.
const finalHtml = fs.readFileSync(indexPath, 'utf8')
const scriptOpen = finalHtml.indexOf('<script>')
const scriptClose = finalHtml.lastIndexOf('</script>')
const styleOpen = finalHtml.indexOf('<style>')
const styleClose = finalHtml.lastIndexOf('</style>')
const bodyOpen = finalHtml.indexOf('<body')
const bodyClose = finalHtml.lastIndexOf('</body>')

if (scriptOpen < 0 || scriptClose <= scriptOpen) throw new Error('Portable HTML has invalid JavaScript boundaries')
if (styleOpen < 0 || styleClose <= styleOpen) throw new Error('Portable HTML has invalid CSS boundaries')
if (bodyOpen <= scriptClose || bodyClose <= bodyOpen) throw new Error('Portable HTML body is not after the inlined assets')
if ((finalHtml.match(/<\/script>/gi) || []).length !== 1) throw new Error('Portable HTML contains an unexpected script closing tag')
if ((finalHtml.match(/<\/style>/gi) || []).length !== 1) throw new Error('Portable HTML contains an unexpected style closing tag')

const markupOutsideScript = finalHtml.slice(0, scriptOpen) + finalHtml.slice(scriptClose + '</script>'.length)
if (/\bsrc=["'][^"']+\.js/i.test(markupOutsideScript)) throw new Error('Portable HTML still references an external JS file')
if (/\bhref=["'][^"']+\.css/i.test(markupOutsideScript)) throw new Error('Portable HTML still references an external CSS file')
if (!finalHtml.includes('<div id="root"></div>')) throw new Error('React root element missing from portable HTML')
if (!finalHtml.includes('name="organizer-portable" content="true"')) throw new Error('Portable marker missing')

console.log(`Portable Organizer Pro Web written: ${indexPath} (${fs.statSync(indexPath).size} bytes)`)
console.log('Validated single-file HTML for direct local opening')
