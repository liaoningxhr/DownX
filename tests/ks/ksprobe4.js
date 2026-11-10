const { chromium } = require('playwright');
const links = [
  ['https://v.kuaishou.com/n3KkLyq3', 'https://www.kuaishou.com/short-video/3x49tg4mzn73p4a'],
  ['https://v.kuaishou.com/KA7zaiw1', 'https://www.kuaishou.com/short-video/3xtufiwjejy979k'],
  ['https://v.kuaishou.com/KwJo59Q1', 'https://www.kuaishou.com/short-video/3xk43y2kz7bvyhu']
];
(async () => {
  const browser = await chromium.launch({
    executablePath: 'F:/DownX-Studio/toolchain/pw-browsers/chromium-1134/chrome-win/chrome.exe',
    proxy: { server: 'http://127.0.0.1:10808' },
    args: ['--autoplay-policy=no-user-gesture-required']
  });
  for (const [short, url] of links) {
    const ctx = await browser.newContext({
      userAgent: 'Mozilla/5.0 (Linux; U; Android 12; zh-cn; 22081212C Build/SKQ1.211103.001) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.0.0 Mobile Safari/537.36',
      viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true
    });
    const page = await ctx.newPage();
    const mp4 = [], jpg = [];
    page.on('request', r => {
      const u = r.url();
      if (/\.mp4|mime_type=video|\.m3u8/.test(u)) mp4.push(u);
      else if (/\.(jpg|jpeg|webp)/i.test(u) && /yximgs|kwimgs/.test(u)) jpg.push(u);
    });
    try { await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 }); } catch (e) {}
    await page.waitForTimeout(9000);
    const t = await page.evaluate(() => ({ title: document.title, n: document.querySelectorAll('video').length }));
    console.log('=====', short, JSON.stringify(t));
    console.log('mp4:', mp4.slice(0, 2).join('\n     '));
    console.log('cover:', jpg.slice(0, 2).join('\n       '));
    await ctx.close();
  }
  await browser.close();
})();
