const { chromium } = require('playwright');
(async () => {
  const urls = process.argv.slice(2);
  const browser = await chromium.launch({
    executablePath: 'F:/DownX-Studio/toolchain/pw-browsers/chromium-1134/chrome-win/chrome.exe',
    proxy: { server: 'http://127.0.0.1:10808' },
    args: ['--autoplay-policy=no-user-gesture-required']
  });
  for (const u of urls) {
    const ctx = await browser.newContext({
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
    });
    const page = await ctx.newPage();
    const media = [];
    page.on('request', r => {
      const url = r.url();
      if (/\.mp4|mime_type=video|\.m3u8|\.jpg|\.png|\.webp/i.test(url)) media.push(url);
    });
    try {
      await page.goto(u, { waitUntil: 'domcontentloaded', timeout: 35000 });
    } catch (e) { console.log('goto err', e.message); }
    await page.waitForTimeout(9000);
    const info = await page.evaluate(() => {
      const v = document.querySelector('video');
      const imgs = [...document.querySelectorAll('img')].map(i => i.currentSrc || i.src).filter(s => /^https?:/.test(s));
      return {
        title: document.title,
        videoSrc: v ? (v.currentSrc || v.src || '') : '',
        poster: v ? (v.poster || '') : '',
        ogImage: (document.querySelector('meta[property="og:image"]') || {}).content || '',
        imgs: imgs.slice(0, 12),
        bodyHead: (document.body ? document.body.innerText : '').slice(0, 120)
      };
    });
    console.log('====', u);
    console.log(JSON.stringify(info, null, 1));
    console.log('-- media reqs --');
    console.log(media.slice(0, 20).join('\n'));
    await ctx.close();
  }
  await browser.close();
})();
