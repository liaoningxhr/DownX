// 抖音分享页探测：页面能不能开、能不能嗅到流、嗅到的流地址直接请求是什么状态
// 用法: node tests/douyin/probe.js https://v.douyin.com/xxxx/ [更多链接…]
const path = require('path');
const { chromium } = require(path.join('F:/DownX-Studio/toolchain/pwtest/node_modules/playwright'));

const UA = 'Mozilla/5.0 (Linux; Android 12; 22081212C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

(async () => {
  const links = process.argv.slice(2);
  if (!links.length) {
    console.log('用法: node probe.js <抖音链接> [更多…]');
    process.exit(1);
  }
  const browser = await chromium.launch({ headless: true });
  for (const link of links) {
    console.log('\n=== ' + link + ' ===');
    const ctx = await browser.newContext({ userAgent: UA, viewport: { width: 412, height: 892 }, locale: 'zh-CN' });
    const page = await ctx.newPage();
    const streams = [];
    const bad = [];
    page.on('response', async (r) => {
      const u = r.url();
      const st = r.status();
      if (/mime_type=video|douyinvod|\.m3u8/.test(u)) streams.push({ u, st });
      if (st >= 400 && /douyin|iesdouyin|douyinvod|byteimg/.test(u)) bad.push(st + ' ' + u.slice(0, 110));
    });
    page.on('requestfailed', (r) => bad.push('FAIL ' + r.failure()?.errorText + ' ' + r.url().slice(0, 100)));

    try {
      const resp = await page.goto(link, { waitUntil: 'domcontentloaded', timeout: 45000 });
      console.log('页面状态:', resp && resp.status(), '  最终URL:', page.url().slice(0, 100));
      await page.waitForTimeout(7000);
      const info = await page.evaluate(() => {
        const v = document.querySelector('video');
        let src = v ? (v.currentSrc || v.src || '') : '';
        if (!src && v) { const s = v.querySelector('source'); if (s) src = s.src; }
        const imgs = [...document.querySelectorAll('img')].map(i => i.currentSrc || i.src)
          .filter(s => /^https?:/.test(s) && !/avatar|icon|logo|emoji/i.test(s)).slice(0, 5);
        return { title: document.title, src, poster: v ? v.poster : '', imgs, bodyLen: document.body.innerText.length,
                 bodyHead: document.body.innerText.slice(0, 120).replace(/\s+/g, ' ') };
      });
      console.log('标题:', info.title.slice(0, 60));
      console.log('页面文字长度:', info.bodyLen, '| 可见文字:', info.bodyHead);
      console.log('video.src:', info.src ? info.src.slice(0, 120) : '(空)');
      console.log('poster:', info.poster ? info.poster.slice(0, 80) : '(空)', '| 图片数:', info.imgs.length);
      console.log('网络里的流请求:', streams.length);
      streams.slice(0, 5).forEach(s => console.log('   ', s.st, s.u.slice(0, 110)));

      // 把嗅到的流地址当 App 那样去请求，看是不是 404
      const target = (streams.find(s => s.st === 200) || {}).u || info.src;
      if (target) {
        const r = await fetch(target, { headers: { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Range: 'bytes=0-1023' } });
        console.log('直连该流:', r.status, r.headers.get('content-type'), 'content-range=' + r.headers.get('content-range'));
        const buf = Buffer.from(await r.arrayBuffer());
        console.log('前 12 字节:', buf.slice(0, 12).toString('hex'), '(mp4 应为 ...66747970)');
      } else {
        console.log('!! 没嗅到任何流地址');
      }
    } catch (e) {
      console.log('打开失败:', e.message.slice(0, 120));
    }
    console.log('异常/失败请求:', bad.length);
    bad.slice(0, 6).forEach(b => console.log('   ', b));
    await ctx.close();
  }
  await browser.close();
})();
