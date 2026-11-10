const path = require('path');
const { chromium } = require(path.join('F:/DownX-Studio/toolchain/pwtest/node_modules/playwright'));
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

(async () => {
  const b = await chromium.launch({ headless: true });
  const ctx = await b.newContext({ userAgent: UA, locale: 'en-US' });
  const p = await ctx.newPage();
  await p.goto('https://example.com/', { timeout: 30000 }).catch(() => {});

  const out = await p.evaluate(async () => {
    const log = [];
    const j = async (u) => {
      try {
        const r = await fetch(u, { credentials: 'omit' });
        const t = await r.text();
        return { s: r.status, t };
      } catch (e) { return { s: -1, t: '', err: String(e) }; }
    };

    // 1) 公开 syndication 时间线，找一条带 video_info 的推文
    const users = ['NASA', 'SpaceX', 'NatGeo', 'BBCNews'];
    let id = null, vid = null;
    for (const u of users) {
      const r = await j('https://syndication.twitter.com/srv/timeline-profile/screen-name/' + u);
      log.push('timeline ' + u + ' status=' + r.s + ' len=' + r.t.length);
      if (r.s !== 200) continue;
      const re = /"rest_id"\s*:\s*"(\d{15,20})"([\s\S]{0,4000})/g;
      let m;
      while ((m = re.exec(r.t))) {
        if (m[2].indexOf('video_info') >= 0) { id = m[1]; break; }
      }
      if (!id) {
        const m2 = r.t.match(/"id_str"\s*:\s*"(\d{15,20})"/);
        if (m2 && r.t.indexOf('video_info') >= 0) id = m2[1];
      }
      if (id) break;
    }
    log.push('找到带视频推文: ' + id);
    if (!id) return { log };

    // 2) tweet-result 拿 variants
    const tr = await j('https://cdn.syndication.twimg.com/tweet-result?id=' + id + '&lang=en');
    log.push('tweet-result status=' + tr.s + ' len=' + tr.t.length + ' hasVideoInfo=' + (tr.t.indexOf('video_info') >= 0));
    let body = tr.t;
    if (tr.s !== 200 || body.indexOf('video_info') < 0) {
      const fx = await j('https://api.fxtwitter.com/status/' + id);
      log.push('fxtwitter status=' + fx.s + ' len=' + fx.t.length);
      body = fx.t;
    }
    const urls = [];
    const re2 = /"url"\s*:\s*"(https:\/\/video\.twimg\.com[^"]+)"/g;
    let mm;
    while ((mm = re2.exec(body))) urls.push(mm[1]);
    log.push('video.twimg.com 变体数: ' + urls.length);
    const shapes = urls.slice(0, 6).map(u => ({
      host: new URL(u).host,
      path: new URL(u).pathname,
      query: new URL(u).search,
      hasOe: /[?&]oe=/.test(u),
      hasSign: /[?&](signature|sig|token|expire|expires|X-Amz|Policy)=/i.test(u)
    }));
    log.push(JSON.stringify(shapes, null, 1));

    // 3) 真实下一个字节，看是否需要 Referer / 是否 403
    if (urls.length) {
      const target = urls.find(u => /\.mp4/.test(u)) || urls[0];
      for (const [label, hdr] of [
        ['无 Referer', { Range: 'bytes=0-63' }],
        ['带 Referer', { Range: 'bytes=0-63', Referer: 'https://x.com/' }]
      ]) {
        try {
          const r = await fetch(target, { headers: hdr });
          const buf = await r.arrayBuffer();
          log.push('下载 ' + label + ' → ' + r.status + ' type=' + r.headers.get('content-type') +
            ' len=' + buf.byteLength + ' head=' + new TextDecoder().decode(new Uint8Array(buf).slice(0, 12)));
        } catch (e) { log.push('下载 ' + label + ' 失败 ' + String(e).slice(0, 80)); }
      }
    }
    return { log, id };
  });

  console.log(out.log.join('\n'));
  await ctx.close();
  await b.close();
})();
