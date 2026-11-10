const path=require('path');
const {chromium}=require(path.join('F:/DownX-Studio/toolchain/pwtest/node_modules/playwright'));
const DT='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
(async()=>{
  const b=await chromium.launch({headless:true});
  // ---------- Instagram ----------
  {
    const ctx=await b.newContext({userAgent:DT,viewport:{width:1280,height:900},locale:'en-US'});
    const p=await ctx.newPage();
    const hits=[];
    p.on('response',r=>{const u=r.url(); if(/cdninstagram|fbcdn/.test(u)) hits.push(u);});
    try{ await p.goto('https://www.instagram.com/instagram/',{waitUntil:'domcontentloaded',timeout:45000}); }catch(e){ console.log('IG 打开失败:', e.message.slice(0,60)); }
    await p.waitForTimeout(7000);
    console.log('IG 页面标题:', (await p.title()).slice(0,50));
    console.log('IG CDN 请求数:', hits.length);
    hits.slice(0,3).forEach(u=>{
      const q = u.split('?')[1]||'';
      console.log('   host=', new URL(u).host);
      console.log('   query 末段:', q.split('&').filter(x=>/^oe=|^oh=|^_nc_sid|^efg|^ig_cache|^stp/.test(x)).join(' | ').slice(0,160));
      console.log('   full:', u.slice(0,150));
    });
    await ctx.close();
  }
  // ---------- X ----------
  {
    const ctx=await b.newContext({userAgent:DT,viewport:{width:1280,height:900},locale:'en-US'});
    const p=await ctx.newPage();
    const hits=[];
    p.on('response',r=>{const u=r.url(); if(/video\.twimg\.com/.test(u)) hits.push({u,s:r.status()});});
    try{ await p.goto('https://x.com/NASA',{waitUntil:'domcontentloaded',timeout:45000}); }catch(e){ console.log('X 打开失败:', e.message.slice(0,60)); }
    await p.waitForTimeout(8000);
    console.log('X 页面标题:', (await p.title()).slice(0,60));
    console.log('video.twimg 请求数:', hits.length);
    hits.slice(0,4).forEach(h=>console.log('   ', h.s, h.u.slice(0,140)));
    await ctx.close();
  }
  await b.close();
})();
