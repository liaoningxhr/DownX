// 抖音提取脚本的确定性主机测试（不联网、不依赖真机）
// 思路：直接从 ShortVideoScraper.java 里抽出 JS 常量（单一来源，避免测试与实现漂移），
// 在假页面上跑，断言：
//   1) 图文页：SSR 的 images 能取到，封面/相关推荐不算作品图；
//   2) 无 SSR 时：DOM 里只认 biz_tag=aweme_images / tplv-dy-aweme-images；
//   3) 视频页：video 取到、workImgs 为空（不能把视频页误判成图文）。
const fs = require('fs');
const path = require('path');
const { chromium } = require(path.join('F:/DownX-Studio/toolchain/pwtest/node_modules/playwright'));

const SRC = 'F:/DownX-Studio/app/app/src/main/java/com/downx/app/ShortVideoScraper.java';

/** 把 Java 源码里 JS 常量那段的所有字符串字面量按顺序解出来拼接 */
function extractJs(javaFile) {
  const src = fs.readFileSync(javaFile, 'utf8');
  const start = src.indexOf('private static final String JS =');
  const end = src.indexOf('private static final String NUDGE =');
  if (start < 0 || end < 0) throw new Error('找不到 JS 常量段');
  const block = src.slice(start, end);
  let out = '';
  let i = 0;
  while (i < block.length) {
    if (block[i] !== '"') { i++; continue; }
    i++;
    let s = '';
    while (i < block.length) {
      const c = block[i];
      if (c === '\\') {
        const n = block[i + 1];
        if (n === 'n') s += '\n';
        else if (n === 't') s += '\t';
        else if (n === 'r') s += '\r';
        else if (n === 'u') { s += String.fromCharCode(parseInt(block.substr(i + 2, 4), 16)); i += 4; }
        else s += n;
        i += 2;
      } else if (c === '"') { i++; break; }
      else { s += c; i++; }
    }
    out += s;
  }
  return out;
}

let ok = 0, fail = 0;
function check(name, cond, detail) {
  if (cond) { ok++; console.log('  [PASS] ' + name + (detail ? ' · ' + detail : '')); }
  else { fail++; console.log('  [FAIL] ' + name + (detail ? ' · ' + detail : '')); }
}

(async () => {
  const JS = extractJs(SRC);
  check('JS 抽取长度 > 2000', JS.length > 2000, JS.length + ' 字符');
  const b = await chromium.launch({ headless: true });
  const ctx = await b.newContext({ userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X)' });

  async function runOn(html) {
    const p = await ctx.newPage();
    await p.setContent(html, { waitUntil: 'domcontentloaded' });
    const raw = await p.evaluate(JS);
    await p.close();
    return JSON.parse(raw);
  }

  // ---------- 1) 图文页（有 SSR） ----------
  const detail = {
    app: {
      videoDetail: {
        desc: '图文测试文案 #话题',
        author: { nickname: '测试作者' },
        images: [
          { url_list: ['https://p3.douyinpic.com/i1?biz_tag=aweme_images'] },
          { url_list: ['https://p3.douyinpic.com/i2?biz_tag=aweme_images'] },
          { urlList: ['https://p3.douyinpic.com/i3'] },
        ],
      },
    },
  };
  const enc = encodeURIComponent(JSON.stringify(detail));
  const r1 = await runOn(`<!DOCTYPE html><html><head><title>图文 - 抖音</title></head><body>
    <script id="RENDER_DATA" type="application/json">${enc}</script>
    <video></video>
    <img src="https://p3-pc-sign.douyinpic.com/cover?biz_tag=pcweb_cover">
    <img src="https://p3-pc-sign.douyinpic.com/rel?WEBPC_RELATED=1&biz_tag=aweme_images">
  </body></html>`);
  check('图文 · SSR 取到 3 张', r1.workImgs && r1.workImgs.length === 3, JSON.stringify(r1.workImgs || []));
  check('图文 · SSR 首图正确', (r1.workImgs || [])[0] === 'https://p3.douyinpic.com/i1?biz_tag=aweme_images');
  check('图文 · 标题来自 desc', r1.title === '图文测试文案 #话题', r1.title);
  check('图文 · 作者来自 SSR', r1.author === '测试作者', r1.author);
  check('图文 · 视频为空', !r1.video, r1.video);

  // ---------- 2) 图文页（无 SSR，只能靠 DOM 判据） ----------
  const r2 = await runOn(`<!DOCTYPE html><html><head><title>图文 - 抖音</title></head><body>
    <video></video>
    <img src="https://p3-pc-sign.douyinpic.com/cover?biz_tag=pcweb_cover">
    <img src="https://p3-pc-sign.douyinpic.com/a?biz_tag=aweme_images&x=1">
    <img src="https://p3-pc-sign.douyinpic.com/b?tplv-dy-aweme-images:q75.webp">
    <img src="https://p3-pc-sign.douyinpic.com/c?WEBPC_RELATED=1&biz_tag=aweme_images">
  </body></html>`);
  check('DOM 兜底 · 只收 2 张作品图', r2.workImgs && r2.workImgs.length === 2, JSON.stringify(r2.workImgs || []));
  check('DOM 兜底 · 排除封面与相关推荐',
    (r2.workImgs || []).every(u => u.indexOf('pcweb_cover') < 0 && u.indexOf('WEBPC_RELATED') < 0));

  // ---------- 3) 视频页：不能被误判成图文 ----------
  const r3 = await runOn(`<!DOCTYPE html><html><head><title>视频 - 抖音</title></head><body>
    <video src="https://v26-web.douyinvod.com/abc/video/tos/cn/x/?mime_type=video_mp4"></video>
    <img src="https://p3-pc-sign.douyinpic.com/cover?biz_tag=pcweb_cover">
  </body></html>`);
  check('视频页 · 进度条拿得到 video', typeof r3.video === 'string' && r3.video.indexOf('douyinvod') >= 0, (r3.video || '').slice(0, 50));
  check('视频页 · workImgs 为空', (r3.workImgs || []).length === 0, JSON.stringify(r3.workImgs || []));

  await ctx.close();
  await b.close();
  console.log('\n结果: ' + ok + ' PASS, ' + fail + ' FAIL');
  process.exit(fail ? 1 : 0);
})();
