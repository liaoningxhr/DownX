const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({
    executablePath: 'F:/DownX-Studio/toolchain/pw-browsers/chromium-1134/chrome-win/chrome.exe',
    proxy: { server: 'http://127.0.0.1:10808' },
    args: ['--autoplay-policy=no-user-gesture-required']
  });
  const ctx = await browser.newContext({
    userAgent: 'Mozilla/5.0 (Linux; Android 12; 22081212C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36',
    viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true
  });
  const page = await ctx.newPage();
  const reqs = [];
  page.on('request', r => reqs.push(r.url()));
  try { await page.goto('https://www.kuaishou.com/short-video/3x49tg4mzn73p4a', { waitUntil: 'domcontentloaded', timeout: 30000 }); } catch (e) {}
  await page.waitForTimeout(9000);
  const info = await page.evaluate(() => {
    const out = {};
    out.frames = [...document.querySelectorAll('iframe')].map(f => f.src).slice(0,5);
    out.videoCount = document.querySelectorAll('video').length;
    const v = document.querySelector('video');
    if (v) { out.v = { src: v.src, cur: v.currentSrc, poster: v.poster, w: v.videoWidth, h: v.videoHeight }; }
    out.canvas = document.querySelectorAll('canvas').length;
    out.html = (document.body ? document.body.innerHTML : '').slice(0, 600);
    return out;
  });
  console.log(JSON.stringify(info, null, 1));
  console.log('--- kwimgs/kwaicdn reqs ---');
  console.log(reqs.filter(u => /kwimgs|kwaicdn|yximgs|wsukwai|wskwai/.test(u)).slice(0, 25).join('\n'));
  console.log('--- all reqs count', reqs.length);
  console.log(reqs.filter(u => /graphql|rest\/|api/.test(u)).slice(0, 10).join('\n'));
  await browser.close();
})();
