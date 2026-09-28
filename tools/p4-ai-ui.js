#!/usr/bin/env node
'use strict';
/* =============================================================================
 *  tools/p4-ai-ui.js —— AI 问答面板的浏览器实测
 *  -----------------------------------------------------------------------------
 *  为什么需要它：
 *    接口层面的测试（集成测试 + p4-ai-e2e.py）只能证明「POST /ai/ask 会返回
 *    一段正确答案」。它们**证明不了**前端那 100 多行 AI 代码是对的 ——
 *    选择器写错、字段名对不上、conversationId 没存下来，这些全都表现为
 *    「接口全绿但页面上什么都不出现」。
 *
 *    这类问题的共同点是：HTTP 全是 200、控制台可能一声不响，但用户看到的是
 *    一个转圈转到天荒地老的「思考中…」。所以必须在真浏览器里点一遍。
 *
 *  它验什么（都是机器判据）：
 *    1. 登录后 #ai-on 显示、#ai-off 隐藏（/ai/status 的返回值真的被用上了）
 *    2. 模型名徽章有内容
 *    3. 5 个示例问题 chip 都在，且都带 data-q
 *    4. 点 chip -> 出现用户气泡 -> 「思考中…」消失 -> 答案气泡有内容且有数字
 *    5. 答案里的 **加粗** 被渲染成 <strong>，不是字面的星号
 *    6. 多轮：输入框再问一句，出现第 2 组气泡；「重新开始」按钮出现（说明
 *       conversationId 被存下来了）—— 这是「多轮对话真的接上了」的前端证据
 *    7. 点「重新开始」-> 会话清空、按钮重新隐藏
 *    8. 全程无 pageerror / console.error
 *
 *  用法：
 *    NODE_PATH=<workspace>/node_modules node tools/p4-ai-ui.js [输出目录]
 *  环境变量：
 *    P4_BASE   默认 http://127.0.0.1:8090/api
 *    P4_SHOTS  默认 <项目>/docs/screenshots
 *  退出码：0 = 全过；1 = 有失败
 * ============================================================================= */

const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright-core');

const BASE = process.env.P4_BASE || 'http://127.0.0.1:8090/api';
const OUT_DIR = process.env.P4_SHOTS || path.join(__dirname, '..', 'docs', 'screenshots');
// 诊断截图（「AI 未启用」和「跑挂了」）写到 target/ 下 —— 那是构建产物目录、不进仓库。
// 这两种图是给人当场看的，不该跟着 README 一起被提交。
const DIAG_DIR = path.join(__dirname, '..', 'target');

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

/**
 * 等「助手这一轮给出结果」—— 结果可能是答案，也可能是错误。
 *
 * ★ 为什么不能只等 `.ai-bubble`：模型超时/上游挂掉时，前端把错误渲染进
 *   `.ai-err`（这是**正确**行为 —— 绝不能把错误显示成「你没有数据」）。
 *   只等 `.ai-bubble` 的话，这种情况会一路等到 30 秒默认超时，
 *   报出来的是「waitForFunction: Timeout 30000ms exceeded」——
 *   一个完全不指向真实原因的失败信息。实测就这么踩过一次。
 *
 * 返回 'answer' | 'error'；两者都没出现则抛出（真超时）。
 *
 * ⚠️ 另一个坑：waitForFunction 的签名是 (pageFunction, arg, options)。
 *    把 {timeout: N} 写在第二个参数上会被当成 arg 丢掉，实际用默认 30 秒。
 *    必须写成 (fn, null, {timeout: N})。
 */
async function waitForAiResult(page, timeoutMs) {
  return page.waitForFunction(
    () => {
      const t = document.getElementById('ai-thread');
      if (!t) return false;
      if (t.querySelector('.ai-err')) return 'error';
      if (t.querySelector('.ai-dots')) return false;
      const bubbles = t.querySelectorAll('.ai-msg.assistant .ai-bubble');
      const last = bubbles[bubbles.length - 1];
      return last && last.innerText.trim().length > 5 ? 'answer' : false;
    },
    null,
    { timeout: timeoutMs }
  ).then(h => h.jsonValue());
}

function shot(page, name) {
  // 只截 AI 面板本身，不截整页 —— 整页图又高又窄，嵌进 README 里看不清重点。
  return page.locator('#ai-panel').screenshot({ path: path.join(OUT_DIR, name) });
}

(async () => {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  console.log('='.repeat(70));
  console.log(` AI 问答面板 · 浏览器实测   目标=${BASE}`);
  console.log('='.repeat(70));

  const browser = await chromium.launch({
    channel: 'chrome',
    headless: true,
    args: ['--no-proxy-server', '--hide-scrollbars'],
  });
  const ctx = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    deviceScaleFactor: 2,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
  });
  const page = await ctx.newPage();

  const jsErrors = [];
  const consoleErrors = [];
  page.on('pageerror', e => jsErrors.push(String(e)));
  page.on('console', m => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });

  try {
    /* ---------------------------------------------------- 1. 登录 */
    console.log('\n[1] 登录');
    await page.goto(BASE + '/', { waitUntil: 'load', timeout: 20000 });
    await page.waitForSelector('#auth-username', { timeout: 10000 });
    await page.fill('#auth-username', 'demo');
    await page.fill('#auth-password', 'demo123456');
    await page.click('#auth-submit');
    await page.waitForSelector('#app-view:not(.hidden)', { timeout: 20000 });
    check('进入主界面', await page.isVisible('#app-view'));

    /* ---------------------------------------------------- 2. 面板状态 */
    console.log('\n[2] AI 面板：/ai/status 的返回值真的被用上了吗');
    await page.waitForSelector('#ai-panel', { timeout: 10000 });
    check('#ai-panel 存在', await page.isVisible('#ai-panel'));

    // /ai/status 是异步的，等它把两个块切换到位。
    // ⚠️ 参数位置：waitForFunction(fn, arg, options) —— options 必须在第三个。
    await page.waitForFunction(
      () => {
        const on = document.getElementById('ai-on');
        const off = document.getElementById('ai-off');
        return on && off && (!on.classList.contains('hidden') || !off.classList.contains('hidden'));
      },
      null,
      { timeout: 20000 }
    ).catch(() => {});

    const onVisible = await page.isVisible('#ai-on');
    const offVisible = await page.isVisible('#ai-off');

    if (!onVisible) {
      console.log('  [SKIP] AI 未启用（页面显示的是「未启用」说明块）');
      check('#ai-off 说明了要配什么', (await page.innerText('#ai-off')).includes('DEEPSEEK_API_KEY'));
      await page.screenshot({ path: path.join(DIAG_DIR, 'ai-disabled.png'), fullPage: true });
      console.log('\n  → 需要 AI_ENABLED=true + DEEPSEEK_API_KEY 才能验问答交互。');
      console.log(`\n 结果: ${passed} 项通过, ${failed} 项失败（AI 未启用，只验了降级 UI）`);
      await browser.close();
      process.exit(failed === 0 ? 0 : 1);
    }

    check('#ai-on 可见（AI 已启用）', onVisible);
    check('#ai-off 隐藏（不能两块同时显示）', !offVisible);

    const model = (await page.innerText('#ai-model')).trim();
    check('模型名徽章有内容', model.length > 0, model);
    const note = (await page.innerText('#ai-note')).trim();
    check('面板副标题提示了「只读 · 可多轮」', note.includes('只读'), note);

    /* ---------------------------------------------------- 3. 示例 chips */
    console.log('\n[3] 示例问题 chips');
    const chips = page.locator('#ai-samples .chip');
    const chipCount = await chips.count();
    check('5 个示例问题都在', chipCount === 5, `实际 ${chipCount} 个`);
    let chipsOk = true;
    for (let i = 0; i < chipCount; i++) {
      const q = await chips.nth(i).getAttribute('data-q');
      if (!q || !q.trim()) { chipsOk = false; }
    }
    check('每个 chip 都带非空 data-q（点了才发得出去）', chipsOk);

    /* ---------------------------------------------------- 4. 点 chip 提问 */
    // 期望值直接取 chip 自己的 data-q，不写死 —— 写死的话改了 chip 文案
    // 就会红一条跟功能无关的测试。
    const Q1 = (await chips.first().getAttribute('data-q')).trim();
    const Q2 = '那我今天任务完成得怎么样？';
    console.log(`\n[4] 点示例 chip 提问：${Q1}`);
    const t0 = Date.now();
    await chips.first().click();

    // ★ 注意选择器要落到 .ai-bubble 上：.ai-msg.user 里还有一个 span.who
    //   写着「你」，直接取 .ai-msg.user 的 innerText 会把角色名一起读进来。
    await page.waitForSelector('#ai-thread .ai-msg.user .ai-bubble', { timeout: 10000 });
    check('用户气泡出现（问题被渲染到对话流里）',
      await page.isVisible('#ai-thread .ai-msg.user .ai-bubble'));

    const userText = (await page.innerText('#ai-thread .ai-msg.user .ai-bubble')).trim();
    check('用户气泡内容 = chip 上的问题', userText === Q1,
      `期望「${Q1}」实际「${userText}」`);
    check('角色名「你」在气泡外面（没有混进问题文本）',
      (await page.innerText('#ai-thread .ai-msg.user')).startsWith('你'));

    // 「思考中…」必须消失，且助手气泡要有真内容
    const outcome1 = await waitForAiResult(page, 120000);
    const elapsed = ((Date.now() - t0) / 1000).toFixed(1);
    if (outcome1 === 'error') {
      const errText = (await page.innerText('#ai-thread .ai-err')).trim();
      check('第 1 轮提问成功（没有落到错误分支）', false, errText);
      throw new Error('第 1 轮提问返回了错误：' + errText);
    }
    // 取最后一条答案（多轮时最后一条才是本轮的）
    const answer = (await page.locator('#ai-thread .ai-msg.assistant .ai-bubble').last().innerText()).trim();
    console.log(`    答案（${elapsed}s）:\n${answer.split('\n').map(l => '      | ' + l).join('\n')}`);

    check('「思考中…」已消失（不是永远转圈）', !(await page.isVisible('#ai-thread .ai-dots')));
    check('答案气泡有实质内容（>20 字符）', answer.length > 20, `${answer.length} 字符`);
    check('答案里带数字（真的查到了数据，不是空话）', /\d/.test(answer));
    check('答案不是错误提示', !/暂时不可用|出错了|失败/.test(answer));

    /* ---------------------------------------------------- 5. 加粗渲染 */
    console.log('\n[5] **加粗** 的渲染');
    const strongCount = await page.locator('#ai-thread .ai-msg.assistant .ai-bubble strong').count();
    const hasLiteralStars = answer.includes('**');
    // 这一条是真正要守的不变式：formatAnswer 必须把 **x** 变成 <strong>，
    // 漏掉的话用户会看到一堆字面星号。
    check('答案里没有字面的 ** 星号', !hasLiteralStars,
      hasLiteralStars ? '说明 formatAnswer 没生效' : '');
    // <strong> 的个数是模型决定的（它可能这轮一个加粗都没用），
    // 所以只报告、不断言 —— 把它写成 check(>= 0) 是假断言，没有价值。
    console.log(`    [INFO] 本轮答案里 <strong> 标签 ${strongCount} 个（模型用不用加粗由它自己决定）`);

    // ★ 表格回归防护。formatAnswer 不认 Markdown 表格，`| a | b |` 会原样显示成
    //   一堆裸竖线（实测出现过）。修法是在 system prompt 里明确禁止表格，
    //   这条断言就是那个约束的「机器可判据」版本。
    check('答案里没有 Markdown 表格（前端不会渲染，会显示成裸竖线）',
      !/^\s*\|.*\|\s*$/m.test(answer),
      '答案里出现了表格行');

    await shot(page, "09-ai-answer.png");

    /* ---------------------------------------------------- 6. 多轮 */
    console.log(`\n[6] 多轮追问：${Q2}`);
    check('「重新开始」按钮已出现（说明 conversationId 存下来了）',
      await page.isVisible('#btn-ai-reset'));

    await page.fill('#ai-input', Q2);
    await page.click('#ai-submit');

    // 等到第 2 组气泡都有内容（同样要能识别错误分支）
    const outcome2 = await waitForAiResult(page, 120000);
    if (outcome2 === 'error') {
      const errText = (await page.innerText('#ai-thread .ai-err')).trim();
      check('第 2 轮提问成功（没有落到错误分支）', false, errText);
      throw new Error('第 2 轮提问返回了错误：' + errText);
    }
    const userBubbles = await page.locator('#ai-thread .ai-msg.user').count();
    const ansBubbles = await page.locator('#ai-thread .ai-msg.assistant .ai-bubble').count();
    check('对话流里有 2 轮（2 个提问 + 2 个回答）',
      userBubbles === 2 && ansBubbles === 2, `user=${userBubbles} assistant=${ansBubbles}`);

    const ans2 = (await page.locator('#ai-thread .ai-msg.assistant .ai-bubble').last().innerText()).trim();
    console.log(`    第 2 轮答案:\n${ans2.split('\n').map(l => '      | ' + l).join('\n')}`);
    check('第 2 轮答案有内容', ans2.length > 20, `${ans2.length} 字符`);
    check('第 2 轮答案带数字', /\d/.test(ans2));
    check('第 2 轮答案也没有表格', !/^\s*\|.*\|\s*$/m.test(ans2));

    await shot(page, "10-ai-multiturn.png");

    /* ---------------------------------------------------- 7. 重新开始 */
    console.log('\n[7] 点「重新开始」');
    await page.click('#btn-ai-reset');
    const afterReset = await page.locator('#ai-thread .ai-msg').count();
    check('对话流被清空', afterReset === 0, `还剩 ${afterReset} 条`);
    check('「重新开始」按钮重新隐藏', !(await page.isVisible('#btn-ai-reset')));

    /* ---------------------------------------------------- 8. 控制台 */
    console.log('\n[8] 控制台');
    check('无未捕获异常', jsErrors.length === 0, jsErrors.slice(0, 2).join(' | '));
    check('无 console.error', consoleErrors.length === 0, consoleErrors.slice(0, 2).join(' | '));

  } catch (e) {
    failed++;
    failures.push('运行期异常: ' + e.message);
    console.log(`\n  [FAIL] 运行期异常: ${e.message}`);
    try { await page.screenshot({ path: path.join(DIAG_DIR, 'ai-FAILED.png'), fullPage: true }); } catch (_) {}
  } finally {
    await browser.close();
  }

  console.log('\n' + '='.repeat(70));
  console.log(` 结果: ${passed} 项通过, ${failed} 项失败`);
  if (failed) console.log(` 失败项: ${failures.join(' / ')}`);
  console.log('='.repeat(70));
  process.exit(failed === 0 ? 0 : 1);
})();
