const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {chromium} = require('../../.build-chat-render/node_modules/playwright-core');
const root = path.resolve(__dirname, '../..');
const assets = path.join(root, 'assets/chat-render');
const out = path.join(root, '.build-chat-render/test-artifacts');
const origin = 'https://appassets.androidplatform.net/assets/chat-render/';
const executablePath = ['C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe'].find(fs.existsSync);
let checks = 0;
function check(condition, message) { assert.ok(condition, message); checks++; console.log('PASS', message); }
async function main() {
  fs.mkdirSync(out, {recursive:true});
  const browser = await chromium.launch({executablePath, headless:true});
  try {
    const context = await browser.newContext({viewport:{width:360,height:640}});
    const fonts = new Set(), external = [], errors = [];
    await context.route('**/*', async route => {
      const url = route.request().url();
      if (!url.startsWith(origin)) { external.push(url); return route.abort(); }
      const target = path.resolve(assets, decodeURIComponent(url.slice(origin.length).split('?')[0]));
      if (!target.startsWith(assets + path.sep) || !fs.existsSync(target)) return route.fulfill({status:404,body:''});
      const ext = path.extname(target);
      const types = {'.html':'text/html','.js':'application/javascript','.css':'text/css',
        '.woff2':'font/woff2','.woff':'font/woff','.ttf':'font/ttf'};
      if (ext.startsWith('.woff') || ext === '.ttf') fonts.add(target);
      await route.fulfill({status:200,contentType:types[ext] || 'application/octet-stream',body:fs.readFileSync(target)});
    });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.addInitScript(() => {
      window.receipts = {height:[],copy:[]};
      window.HarnessMateDisplay = {height:n=>window.receipts.height.push(n),copy:s=>window.receipts.copy.push(s)};
    });
    await page.goto(origin + 'index.html');
    await page.waitForFunction(() => !!window.HarnessMateRenderer);
    async function render(source, dark=false, scale=1) {
      await page.evaluate(({source,dark,scale}) => window.HarnessMateRenderer.setSource(source,
        {text:dark?'#f0f0f0':'#151515',muted:dark?'#aaaaaa':'#666666',surface:dark?'#252525':'#f3f3f3',
          border:dark?'#555555':'#dddddd',fontScale:scale}), {source,dark,scale});
      await page.evaluate(() => document.fonts.ready);
      // Match the native container beneath the transparent Android WebView.
      await page.evaluate(dark => document.documentElement.style.backgroundColor=dark?'#0d0d0d':'#ffffff',dark);
      await page.waitForTimeout(100);
    }
    const code = String.raw`const value = '$x$';`;
    const md = ['# 中文标题','**粗体** 和 _斜体_ 与 [链接](https://example.org)',
      '> 引用','','1. 第一项','2. 第二项','','- 项目甲','- 项目乙','',
      '| 名称 | 值 |','| --- | --- |','| 中文 | 42 |','','```javascript',code,'```'].join('\n');
    await render(md);
    for (const selector of ['h1','strong','em','a[href="https://example.org"]','blockquote','ol','ul','.table-scroll table','pre code'])
      check(await page.locator('#content '+selector).count() > 0, 'Markdown '+selector);
    await page.locator('.copy-code').click();
    check(await page.evaluate(() => window.receipts.copy.at(-1)) === code+'\n', 'Exact code copy');
    await page.locator('.copy-source').click();
    check(await page.evaluate(() => window.receipts.copy.at(-1)) === md, 'Exact Markdown copy');
    for (const [source,selector] of [['_italic_','em'],['*italic*','em'],['[链接](https://example.org)','a'],
      ['标题\n===','h1'],['段落\n\n---\n\n段落','hr'],['段落\n\n    code','pre code']]) {
      await render(source); check(await page.locator('#content '+selector).count() > 0,'Standalone '+selector);
    }
    const math = String.raw`行内 $E=mc^2$ 和 \(a^2+b^2=c^2\)。

$$\int_0^{\infty}e^{-x^2}dx=\frac{\sqrt{\pi}}{2}$$

\[\sum_{k=1}^n k=\frac{n(n+1)}2\]`;
    await render(math);
    check(await page.locator('.katex').count() === 4,'Four math delimiters render');
    check(await page.locator('.katex-display').count() === 2,'Two display formulas');
    check(await page.locator('.katex-error').count() === 0,'Valid TeX has no errors');
    check(await page.locator('msqrt').count() > 0 && await page.locator('mfrac').count() > 0,'Square root and fraction MathML');
    await page.locator('.copy-source').click();
    check(await page.evaluate(() => window.receipts.copy.at(-1)) === math,'Exact TeX copy');
    check(fonts.size > 0,'Fonts served from local assets');
    check(await page.evaluate(() => [...document.fonts].some(f=>f.family.includes('KaTeX') && f.status==='loaded')),'KaTeX fonts loaded');
    await render(['`$x$`','```',String.raw`\[x^2\]`,'```'].join('\n'));
    check(await page.locator('.katex').count() === 0,'Code preserves math literally');
    await render(String.raw`前文 $\frac{1$ 后文`);
    check(await page.locator('#content').innerText().then(s=>s.includes('前文') && s.includes('后文')),'Malformed TeX preserves surrounding text');
    check(await page.locator('.katex-error').count() > 0,'Malformed TeX visibly falls back');
    await render(['<script>window.pwned=1</script>','<iframe src="https://bad.invalid"></iframe>',
      '<img src="https://bad.invalid/x" onerror="window.pwned=1">','[坏链接](javascript:window.pwned=1)',
      String.raw`$\href{javascript:window.pwned=1}{bad}$`,'![图片说明](https://bad.invalid/track)'].join('\n'));
    check(await page.locator('#content script,#content iframe,#content img,#content a[href^="javascript:"]').count() === 0,'Unsafe elements and schemes absent');
    check(await page.evaluate(() => !window.pwned),'Model script never executes');
    check((await page.locator('#content').innerText()).includes('图片说明'),'Remote images become labels');
    const wide = md+'\n\n'+String.raw`$$\sum_{i=1}^{100}\left(\frac{a_i+b_i+c_i+d_i+e_i+f_i+g_i+h_i}{x_i+y_i+z_i}\right)=\int_0^\infty f(x)dx$$`;
    for (const dark of [false,true]) {
      await render(wide,dark);
      check(await page.evaluate(() => document.documentElement.scrollWidth<=innerWidth+1),'360px page contains horizontal overflow');
      check(await page.evaluate(() => window.receipts.height.at(-1)===Math.ceil(document.getElementById('content').getBoundingClientRect().height)),'Reported height matches content');
      await page.screenshot({path:path.join(out,dark?'render-dark-360px.png':'render-light-360px.png'),fullPage:true});
    }
    const before = await page.evaluate(() => window.receipts.height.at(-1));
    await render(wide,true,1.25);
    check(await page.evaluate(() => window.receipts.height.at(-1)) > before,'Font scale updates height');
    check(external.length===0,'Zero external requests');
    check(errors.length===0,'Zero page script errors');
    fs.writeFileSync(path.join(out,'summary.json'),JSON.stringify({status:'HOST_BROWSER_PASS',checks,external,errors,fonts:fonts.size,device:'DEVICE_NOT_RUN'},null,2));
    console.log('HOST_BROWSER_PASS',checks);
  } finally { await browser.close(); }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
