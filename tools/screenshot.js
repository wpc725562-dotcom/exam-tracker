#!/usr/bin/env node
'use strict';
/* =============================================================================
 *  tools/screenshot.js —— 用系统 Chrome 打开页面截图，并**机器判定页面是否真的渲染**
 *  -----------------------------------------------------------------------------
 *  为什么需要这个脚本（而不是人眼看一眼）：
 *
 *    静态前端最容易出的不是「报错」而是「白屏」—— HTTP 200、资源也都 200，
 *    但 JS 里一个选择器写错、一个字段名对不上，整页就是一片空白，
 *    而所有网络层检查**全是绿的**。光靠 curl 看状态码完全发现不了。
 *
 *    所以这里同时做两件事：
 *      (a) 截图 —— 给人看
 *      (b) 断言 —— 给机器判：关键元素在不在、有没有高度、SVG 画没画出来、
 *                  控制台有没有未捕获异常。这些才是「渲染成功」的判据。
 *
 *  为什么用 playwright-core + 系统 Chrome：
 *    - `chrome --headless --screenshot` 只能截静态页，**无法完成登录交互**
 *      （要填表单、点按钮、等异步数据回来），所以必须能驱动页面。
 *    - playwright-core 已在 node workspace 里，且 channel:'chrome' 直接复用
 *      系统已装的 Chrome，不用再下 100+MB 的浏览器二进制。
 *
 *  为什么加 --no-proxy-server：
 *    本机环境有 CODEBUDDY_SERVICE_PROXY_URL，Chrome 走系统代理时会把
 *    127.0.0.1 也劫持走，表现为「页面打不开」，非常难查。
 *
 *  用法：
 *    NODE_PATH=<workspace>/node_modules node tools/screenshot.js [输出目录]
 *  环境变量：
 *    P4_BASE   默认 http://127.0.0.1:8090/api
 *    P4_SHOTS  默认 <项目>/docs/screenshots
 * ============================================================================= */

const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright-core');

const BASE = process.env.P4_BASE || 'http://127.0.0.1:8090/api';
const OUT_DIR = process.env.P4_SHOTS || path.join(__dirname, '..', 'docs', 'screenshots');

let passed = 0;
let failed = 0;
const failures = [];

function check(name, ok, detail) {
  if (ok) {
    passed++;
    console.log(`  [PASS] ${name}${detail ? '  (' + detail + ')' : ''}`);
  } else {
    failed++;
    failures.push(name);
    console.log(`  [FAIL] ${name}${detail ? '  (' + detail + ')' : ''}`);
  }
}

function shot(page, name, opts) {
  const p = path.join(OUT_DIR, name);
  return page.screenshot(Object.assign({ path: p }, opts || {})).then(() => p);
}

(async () => {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  console.log('='.repeat(68));
  console.log(` 截图 + 渲染自检   目标=${BASE}`);
  console.log(` 输出目录=${OUT_DIR}`);
  console.log('='.repeat(68));

  const browser = await chromium.launch({
    channel: 'chrome',
    headless: true,
    args: ['--no-proxy-server', '--hide-scrollbars'],
  });
  const ctx = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 2,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
  });
  const page = await ctx.newPage();

  const jsErrors = [];   // 未捕获异常 / 未处理的 promise rejection
  const consoleErrors = []; // console.error
  page.on('pageerror', e => jsErrors.push(String(e)));
  page.on('console', m => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });

  const written = [];

  try {
    /* ---------------------------------------------------------------- 1. 登录页 */
    console.log('\n[1] 登录页（匿名可打开）');
    const resp = await page.goto(BASE + '/', { waitUntil: 'load', timeout: 20000 });
    check('HTTP 200', !!resp && resp.status() === 200, `status=${resp && resp.status()}`);
    check('页面标题正确', (await page.title()).includes('备考'), await page.title());

    await page.waitForSelector('#login-view', { timeout: 10000 });
    check('#login-view 存在且可见', await page.isVisible('#login-view'));
    check('#app-view 初始为隐藏', !(await page.isVisible('#app-view')));

    const loginText = (await page.innerText('#login-view')).trim();
    check('登录页有可见文字（非白屏）', loginText.length > 20, `${loginText.length} 字符`);
    check('演示账号提示已展示', loginText.includes('demo123456'));

    // 样式表真的生效了吗 —— 白屏的另一种形态是「HTML 出来了但 CSS 没加载」
    const cardBg = await page.locator('.login-card').evaluate(
      el => getComputedStyle(el).backgroundColor
    );
    check('CSS 已生效（登录卡片有背景色）',
      cardBg && cardBg !== 'rgba(0, 0, 0, 0)' && cardBg !== 'transparent', cardBg);

    written.push(await shot(page, '01-login.png'));

    /* ---------------------------------------------------------------- 2. 登录 */
    console.log('\n[2] 登录并进入主界面');
    await page.fill('#auth-username', 'demo');
    await page.fill('#auth-password', 'demo123456');
    await page.click('#auth-submit');

    await page.waitForSelector('#app-view:not(.hidden)', { timeout: 20000 });
    check('登录成功，主界面已显示', await page.isVisible('#app-view'));
    check('登录页已隐藏', !(await page.isVisible('#login-view')));

    // 等异步数据回来：四个概览卡片从 "—" 变成真实数字
    await page.waitForFunction(() => {
      const el = document.querySelector('#c-today-rate');
      return el && el.textContent.trim() !== '—' && el.textContent.trim() !== '';
    }, { timeout: 20000 });
    await page.waitForTimeout(700); // 让二次渲染/过渡稳定

    /* -------------------------------------------------- 3. 主界面渲染完整性 */
    console.log('\n[3] 主界面渲染完整性（白屏检测）');

    const cardCount = await page.locator('#cards .card').count();
    check('概览卡片 4 张', cardCount === 4, `${cardCount} 张`);

    const cardHeights = await page.locator('#cards .card')
      .evaluateAll(els => els.map(e => Math.round(e.getBoundingClientRect().height)));
    check('卡片都有实际高度（未塌陷）',
      cardHeights.length === 4 && cardHeights.every(h => h > 40), JSON.stringify(cardHeights));

    const cardValues = await page.locator('#cards .card .value').allInnerTexts();
    check('卡片数值都已填充（无残留 "—"）',
      cardValues.every(v => v.trim() && !v.includes('—')),
      cardValues.map(v => v.replace(/\s+/g, '')).join(' / '));

    const taskRows = await page.locator('#task-list > *').count();
    check('任务列表有内容', taskRows > 0, `${taskRows} 行`);

    const svgCount = await page.locator('#trend-chart svg').count();
    check('趋势图 SVG 已渲染', svgCount === 1, `${svgCount} 个 <svg>`);

    const barCount = await page.locator('#trend-chart svg rect').count();
    check('趋势图有柱体', barCount > 5, `${barCount} 个 <rect>`);

    const boardRows = await page.locator('#board > *').count();
    check('四科看板有内容', boardRows > 0, `${boardRows} 行`);

    const userName = (await page.innerText('#user-name')).trim();
    check('右上角显示当前用户', userName.length > 0, userName);

    // 整页长图（含任务列表全部 37 行 + 趋势图 + 看板），用来一眼看全貌
    written.push(await shot(page, '02-dashboard-full.png', { fullPage: true }));
    // 首屏，README 第 2 节主图用的就是这张
    written.push(await shot(page, '03-dashboard-top.png'));

    /* ---------------------------------------------------- 4. 任务面板交互 */
    console.log('\n[4] 任务面板（tab 切换 + 新建表单）');
    const allRows = await page.locator('#task-list > *').count();

    await page.click('#status-tabs [data-status="DONE"]');
    await page.waitForTimeout(500);
    const doneRows = await page.locator('#task-list > *').count();
    check('切到「已完成」后列表有内容', doneRows > 0, `${doneRows} 行`);
    check('筛选真的生效了（行数变化）', doneRows !== allRows, `全部 ${allRows} → 已完成 ${doneRows}`);
    written.push(await shot(page, '04-tasks-done.png'));

    await page.click('#status-tabs [data-status=""]');
    await page.waitForTimeout(400);

    await page.click('#btn-toggle-form');
    await page.waitForTimeout(400);
    check('「新建任务」表单可展开', await page.isVisible('#create-form'));
    const subjOpts = await page.locator('#f-subject option').count();
    check('新建表单的科目下拉已填充', subjOpts >= 4, `${subjOpts} 个选项`);
    written.push(await shot(page, '05-create-form.png'));

    await page.click('#btn-toggle-form'); // 收起，避免影响后面的区域截图
    await page.waitForTimeout(300);

    /* ------------------------------------------------------ 5. 局部特写 */
    console.log('\n[5] 关键区域特写');
    const trendPanel = page.locator('.panel').filter({ has: page.locator('#trend-chart') });
    await trendPanel.screenshot({ path: path.join(OUT_DIR, '06-trend-chart.png') });
    written.push(path.join(OUT_DIR, '06-trend-chart.png'));

    const boardPanel = page.locator('.panel').filter({ has: page.locator('#board') });
    await boardPanel.screenshot({ path: path.join(OUT_DIR, '07-board.png') });
    written.push(path.join(OUT_DIR, '07-board.png'));

    const taskPanel = page.locator('.panel').filter({ has: page.locator('#task-list') });
    await taskPanel.screenshot({ path: path.join(OUT_DIR, '08-task-list.png') });
    written.push(path.join(OUT_DIR, '08-task-list.png'));

    /* -------------------------------------------------------- 6. 控制台 */
    console.log('\n[6] 运行时健康度');
    check('无未捕获的 JS 异常', jsErrors.length === 0, jsErrors.slice(0, 3).join(' | '));
    check('无 console.error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));

  } catch (err) {
    failed++;
    failures.push('脚本异常中断：' + err.message);
    console.log(`\n  [FAIL] 脚本异常中断：${err.message}`);
    // 现场快照 —— 白屏排查最需要的就是「崩的那一刻长什么样」
    try {
      const p = path.join(OUT_DIR, '99-failure.png');
      await page.screenshot({ path: p, fullPage: true });
      console.log(`  崩溃现场已保存：${p}`);
    } catch (_) { /* 连截图都失败就算了 */ }
  } finally {
    await browser.close();
  }

  console.log('\n' + '='.repeat(68));
  console.log(` 结果：${passed} 通过 / ${failed} 失败`);
  if (failures.length) console.log(` 失败项：${failures.join('；')}`);
  console.log(` 截图 ${written.length} 张，目录：${OUT_DIR}`);
  console.log('='.repeat(68));
  process.exit(failed === 0 ? 0 : 1);
})();
