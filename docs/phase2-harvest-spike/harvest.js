const { chromium } = require('playwright');
const port = process.argv[2];
const screens = [['SBO', '/nso/sbo?game=$G'], ['Main display', '/views/standard'], ['Penalty tracking (team 1)', '/nso/plt?pos=pt&team=1&game=$G']];
(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  // learn the current game id the same way index.html does
  const p0 = await ctx.newPage(); let game = null;
  p0.on('websocket', ws => ws.on('framereceived', f => { const m = /"ScoreBoard\.CurrentGame\.Game"\s*:\s*"([^"]+)"/.exec(f.payload); if (m) game = m[1]; }));
  await p0.goto(`http://127.0.0.1:${port}/`); for (let i = 0; i < 50 && !game; i++) await p0.waitForTimeout(100); await p0.close();
  const out = {};
  for (const [name, path] of screens) {
    const page = await ctx.newPage(); const regs = []; let recvBytes = 0, recvFrames = 0;
    page.on('websocket', ws => {
      ws.on('framesent', f => { try { const j = JSON.parse(f.payload); if (j.action === 'Register') regs.push(...(j.paths || [])); } catch (e) {} });
      ws.on('framereceived', f => { recvBytes += f.payload.length; recvFrames++; });
    });
    await page.goto(`http://127.0.0.1:${port}${path.replace('$G', game)}`); await page.waitForTimeout(4000);
    out[name] = { paths: [...new Set(regs)], recvBytesFirst4s: recvBytes, recvFrames };
    await page.close();
  }
  console.log(JSON.stringify({ game, out }, null, 1)); await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
