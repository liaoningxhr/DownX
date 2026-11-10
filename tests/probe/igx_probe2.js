const path = require('path');
const { chromium } = require(path.join('F:/DownX-Studio/toolchain/pwtest/node_modules/playwright'));
const DT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const codes = ['C8g7WIZM6Z', 'DercauFo80K'];

(async () => {
  const b = await chromium.launch({ headless: true });
  const ctx = await b.newContext({ userAgent: DT, viewport: { width: 1280, height: 900 }, locale: 'en-US' });
  const p = await ctx.newPage();
  await p.goto('https://www.instagram.com/', { waitUntil: 'domcontentloaded', timeout: 45000 }).catch(() => {});

  for (const c of codes) {
    for (const kind of ['p', 'reel']) {
      const url = 'https://www.instagram.com/' + kind + '/' + c + '/embed/captioned/';
      const r = await p.evaluate(async (u) => {
        try {
          const res = await fetch(u, { credentials: 'omit' });
          const t = await res.text();
          const re = new RegExp('https://[^"\'\\\\ ]*(cdninstagram|fbcdn)[^"\'\\\\ ]*', 'g');
          const hits = (t.match(re) || []).slice(0, 2);
          return {
            s: res.status,
            len: t.length,
            hasVideoUrl: t.indexOf('video_url') >= 0,
            hasDisplay: t.indexOf('display_url') >= 0,
            hasOe: t.indexOf('oe=') >= 0,
            hasLogin: t.indexOf('loginForm') >= 0,
            hits: hits.map(h => h.slice(0, 220))
          };
        } catch (e) { return { err: String(e).slice(0, 90) }; }
      }, url);
      console.log(kind, c, JSON.stringify(r).slice(0, 500));
    }
  }
  await ctx.close();
  await b.close();
})();
