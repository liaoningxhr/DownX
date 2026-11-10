const { chromium } = require('playwright');
const UAS = {
  win141: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36',
  android: 'Mozilla/5.0 (Linux; Android 12; 22081212C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36',
  androidWV: 'Mozilla/5.0 (Linux; Android 12; 22081212C Build/SKQ1.211103.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.0.0 Mobile Safari/537.36'
};
(async () => {
  const url = 'https://www.kuaishou.com/short-video/3x49tg4mzn73p4a';
  for (const [name, ua] of Object.entries(UAS)) {
    const browser = await chromium.launch({
      executablePath: 'F:/DownX-Studio/toolchain/pw-browsers/chromium-1134/chrome-win/chrome.exe',
      proxy: { server: 'http://127.0.0.1:10808' },
      headless: true,
      args: ['--autoplay-policy=no-user-gesture-required', '--disable-blink-features=AutomationControlled']
    });
    const ctx = await browser.newContext({ userAgent: ua, viewport: { width: 390, height: 844 } });
    const page = await ctx.newPage();
    const streams = [];
    page.on('request', r => { const u = r.url(); if (/\.mp4|mime_type=video|\.m3u8/.test(u)) streams.push(u); });
    try { await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 }); } catch (e) {}
    await page.waitForTimeout(8000);
    const info = await page.evaluate(() => {
      const v = document.querySelector('video');
      return {
        title: document.title,
        body: (document.body ? document.body.innerText : '').slice(0, 100).replace(/\n/g, '|'),
        videoSrc: v ? (v.currentSrc || v.src || '') : '',
        poster: v ? (v.poster || '') : '',
        imgs: [...document.querySelectorAll('img')].map(i => i.currentSrc || i.src).filter(s => /^https?:/.test(s)).length
      };
    });
    console.log('=====', name, JSON.stringify(info));
    console.log('streams:', streams.slice(0, 5).join('\n'));
    await browser.close();
  }
})();
