#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""tools/p4-ai-e2e.py —— AI 层的真实端到端验证（真实 LLM + 真实 MySQL + 真实 HTTP）

为什么需要它：
    AI 层的单元测试全部把 ChatClient mock 掉了（不真调模型），集成测试只验
    「接口契约 + 未启用时的降级」。**没有任何一个自动化测试验的是
    「模型到底有没有把问题翻译成正确的工具调用、参数对不对、答案里的数字是不是真的」。**
    那部分只能靠对着真实实例真问一遍，然后把答案里的每个数字拿 REST 接口核对。

它验 5 件事（每件都必须有机器判据，不靠人眼看）：
    1. 单轮：一句话里两个意图 -> 模型应并行调两个工具
    2. 数字：答案里的关键数字与 /stats/* /checkins 直接查出来的完全一致
    3. 多轮：追问「那英语呢？」-> 应继承上一轮的时间范围，而不是重新问「哪段时间」
    4. 纠错：问一个不存在的科目 -> 工具抛异常（带候选列表）-> 模型应改用 listSubjects
             并给出正确答案，同时 degraded=true（有工具失败过）
    5. 隔离：全新用户问同样的问题 -> 不能出现 demo 的任何数字（零泄漏）

用法：
    # 应用需已在 8090 运行，且 AI 已启用（AI_ENABLED=true + DEEPSEEK_API_KEY）
    python tools/p4-ai-e2e.py
环境变量：
    P4_BASE  默认 http://127.0.0.1:8090/api
退出码：0 = 全部通过；1 = 有失败
"""

from __future__ import print_function

import datetime
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("P4_BASE", "http://127.0.0.1:8090/api")
DEMO = ("demo", "demo123456")
# 探针用户名用 it_ 前缀，这样 ExamTrackerIT 的 @AfterAll 清理会顺手收掉它 ——
# 不给自己留一个「跑完必须记得手工删」的坑。
PROBE_USER = "it_ai_probe_%d" % (int(time.time()) % 100000000)
PROBE_PASS = "Probe2026Pass"

# 显式不走代理 —— 本机有系统代理，会把 127.0.0.1 也劫持走
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

passed = 0
failed = 0


def check(ok, label, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print("  [PASS] %s" % label)
    else:
        failed += 1
        print("  [FAIL] %s" % label)
        if detail:
            print("         %s" % detail)
    return ok


def call(method, path, body=None, token=None, timeout=90):
    """返回 (status, json)。4xx/5xx 不抛异常，交给断言判。"""
    url = BASE + path
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with OPENER.open(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8")
            return r.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw}


def ask(token, question, conversation_id=None):
    body = {"question": question}
    if conversation_id:
        body["conversationId"] = conversation_id
    return call("POST", "/ai/ask", body, token)


def minutes_mentioned(minutes, text):
    """判断一个「分钟数」是否被答案提到 —— 允许两种等价写法。

    模型可能写「215 分钟」，也可能换算成「3 小时 35 分钟」。后者同样正确，
    只查 "215" 会误判成「没提到」。所以两种都认。
    """
    if str(minutes) in text:
        return True
    h, m = divmod(int(minutes), 60)
    if h == 0:
        return False
    # 「3 小时 35 分钟」/「3小时35分」/「3 小时零 35 分钟」都认
    for sep in ("", " ", "零", " 零 "):
        if ("%d 小时%s%d" % (h, sep, m)) in text or ("%d小时%s%d" % (h, sep, m)) in text:
            return True
    if m == 0 and ("%d 小时" % h) in text:
        return True
    return False


def main():
    print("=" * 78)
    print(" AI 层端到端验证（真实 LLM + 真实 MySQL）  BASE=%s" % BASE)
    print("=" * 78)

    # ---- 0. 前置：先登录，再问 /ai/status ----
    # 注意顺序：/ai/status 虽然不返回任何用户数据，但它**需要登录**
    # （不在 SecurityConfig.PUBLIC_PATHS 里）。所以必须先拿 token。
    st, res = call("POST", "/auth/login", {"username": DEMO[0], "password": DEMO[1]})
    assert st == 200, "demo 登录失败: %s %s" % (st, res)
    token = res["data"]["token"]
    print("\n[前置] demo 登录成功")

    st, res = call("GET", "/ai/status", token=token)
    if st != 200 or not (res or {}).get("data", {}).get("enabled"):
        print("\n[SKIP] AI 未启用（/ai/status -> %s %s）" % (st, res))
        print("       需要 AI_ENABLED=true 且提供 DEEPSEEK_API_KEY。")
        return 2
    model = res["data"].get("model")
    print("[前置] AI 已启用，模型 = %s" % model)

    # ---- 1. 单轮 + 并行工具调用 ----
    print("\n--- 1. 单轮提问：一句话两个意图 ---")
    q1 = "我这周数学做了多久？顺便说说我今天的任务完成得怎么样。"
    t0 = time.time()
    st, res = ask(token, q1)
    elapsed = time.time() - t0
    print("    问题: %s" % q1)
    print("    HTTP %s  用时 %.2fs" % (st, elapsed))
    if not check(st == 200, "HTTP 200", "实际 %s / %s" % (st, res)):
        return 1
    d = res["data"]
    answer = d["answer"]
    tools = d.get("toolsUsed") or []
    print("    toolsUsed = %s" % tools)
    print("    degraded  = %s" % d.get("degraded"))
    print("    答案:\n%s" % "\n".join("      | " + ln for ln in answer.splitlines()))

    check(len(tools) >= 2, "一句话里两个意图 -> 至少调了 2 个工具",
          "toolsUsed=%s" % tools)
    check("getDailyMinutes" in tools, "调了 getDailyMinutes（时长问题）")
    check("getOverview" in tools, "调了 getOverview（今日完成情况）")
    check(d.get("degraded") is False, "无工具失败 -> degraded=false",
          "实际 %s" % d.get("degraded"))
    check(bool(d.get("conversationId")), "响应里返回了 conversationId（多轮要用）")
    conv = d.get("conversationId")

    # ---- 2. 数字核对（答案里的数字必须能对上数据库）----
    print("\n--- 2. 数字核对：拿 REST 接口直接查，和答案比 ---")
    st, sb = call("GET", "/subjects", token=token)
    subjects = {s["name"]: s["id"] for s in sb["data"]}
    print("    科目: %s" % subjects)

    math_id = subjects.get("数学")
    st, ck = call("GET", "/checkins?subjectId=%d&page=1&size=100" % math_id, token=token)
    # 只统计本周（周一起）
    today = datetime.date.today()
    monday = today - datetime.timedelta(days=today.weekday())
    week_minutes = sum(
        c["actualMinutes"] for c in ck["data"]["items"]
        if monday <= datetime.date(*[int(x) for x in c["checkinDate"].split("-")]) <= today
    )
    print("    数学本周（%s ~ %s）实际分钟 = %d" % (monday, today, week_minutes))
    check(minutes_mentioned(week_minutes, answer),
          "答案里出现了数学本周分钟数 %d（与 /checkins 逐条相加一致）" % week_minutes,
          "答案中未找到该数字")

    st, ov = call("GET", "/stats/overview", token=token)
    o = ov["data"]
    print("    /stats/overview 原始值: 今日任务=%s 已完成=%s 计划=%s 实际=%s 连续=%s 最长=%s 累计天数=%s 累计分钟=%s 倒计时=%s"
          % (o.get("todayTotalTasks"), o.get("todayDoneTasks"), o.get("todayPlannedMinutes"),
             o.get("todayActualMinutes"), o.get("currentStreakDays"), o.get("longestStreakDays"),
             o.get("totalCheckinDays"), o.get("totalActualMinutes"), o.get("daysUntilExam")))

    # ★ 分两档，因为模型有权「精简」：
    #   问题问的是「数学多久 + 今天任务完成得怎么样」，所以**这两个问题直接涉及的
    #   数字必须出现**；而「连续几天 / 累计多少 / 距考试几天」属于模型自己加的
    #   「顺便一提」，它可能加也可能不加 —— 把它们也设成必过会让测试随机变红，
    #   那种测试没人会信。所以：出现了就核对（防编造），不出现不算失败。
    #
    #   字段名取自 /stats/overview 的真实响应（不是猜的）。
    mandatory = [("todayTotalTasks", "今日任务数"), ("todayPlannedMinutes", "今日计划分钟")]
    optional = [("todayDoneTasks", "今日已完成数"),
                ("currentStreakDays", "当前连续打卡天数"),
                ("longestStreakDays", "历史最长连续天数"),
                ("totalCheckinDays", "累计打卡天数"),
                ("totalActualMinutes", "累计投入分钟"),
                ("totalTasks", "累计任务数"),
                ("daysUntilExam", "距考试天数")]

    for key, label in mandatory:
        v = o.get(key)
        # 分钟类的值允许「215 分钟」和「3 小时 35 分钟」两种写法
        ok = v is not None and (minutes_mentioned(v, answer) if "Minutes" in key
                                else str(v) in answer)
        check(ok, "★ 答案里必须出现 %s = %s（问题直接问到的）" % (label, v),
              "答案中未找到 %s" % v)

    for key, label in optional:
        v = o.get(key)
        if v is None:
            check(False, "%s 在 /stats/overview 里不存在（字段名改了？）" % label)
        elif (minutes_mentioned(v, answer) if "Minutes" in key else str(v) in answer):
            check(True, "答案提到了 %s = %s，与 /stats/overview 一致" % (label, v))
        else:
            print("    [INFO] 答案未提及 %s（=%s）—— 模型精简掉了，不算失败" % (label, v))

    # ---- 3. 多轮：追问要继承上下文 ----
    print("\n--- 3. 多轮对话：追问「那英语呢？」 ---")
    st, res = ask(token, "那英语呢？", conv)
    print("    HTTP %s" % st)
    if check(st == 200, "第二轮 HTTP 200"):
        d2 = res["data"]
        print("    toolsUsed = %s" % (d2.get("toolsUsed") or []))
        print("    答案:\n%s" % "\n".join("      | " + ln for ln in d2["answer"].splitlines()))
        eng_id = subjects.get("英语")
        st, ck2 = call("GET", "/checkins?subjectId=%d&page=1&size=100" % eng_id, token=token)
        eng_week = sum(
            c["actualMinutes"] for c in ck2["data"]["items"]
            if monday <= datetime.date(*[int(x) for x in c["checkinDate"].split("-")]) <= today
        )
        print("    英语本周实际分钟 = %d" % eng_week)
        check(minutes_mentioned(eng_week, d2["answer"]),
              "追问答案里出现了英语本周分钟数 %d —— 说明继承了上一轮的时间范围" % eng_week,
              "答案中未找到该数字")
        check(d2.get("conversationId") == conv,
              "第二轮沿用同一个 conversationId（会话未被打断）",
              "第一轮 %s / 第二轮 %s" % (conv, d2.get("conversationId")))

    # ---- 4. 参数纠错：不存在的科目 ----
    print("\n--- 4. 参数纠错：问一个不存在的科目「物理」 ---")
    st, res = ask(token, "我物理这周学了多久？")
    print("    HTTP %s" % st)
    if check(st == 200, "HTTP 200（工具报错不该让整个请求 500）"):
        d3 = res["data"]
        print("    toolsUsed = %s" % (d3.get("toolsUsed") or []))
        print("    degraded  = %s" % d3.get("degraded"))
        print("    答案:\n%s" % "\n".join("      | " + ln for ln in d3["answer"].splitlines()))
        check("listSubjects" in (d3.get("toolsUsed") or []),
              "模型改用 listSubjects 去查真实科目（自我纠错）",
              "toolsUsed=%s" % d3.get("toolsUsed"))
        check(d3.get("degraded") is True,
              "有过工具失败 -> degraded=true（前端可据此提示「数据可能不全」）",
              "实际 %s" % d3.get("degraded"))
        for name in subjects:
            check(name in d3["answer"],
                  "答案里列出了真实科目「%s」（而不是编一个「物理」出来）" % name)

    # ---- 5. 跨用户隔离 ----
    print("\n--- 5. 跨用户隔离：全新用户问同样的问题 ---")
    st, res = call("POST", "/auth/register",
                   {"username": PROBE_USER, "password": PROBE_PASS, "nickname": PROBE_USER})
    check(st == 201, "注册探针用户 %s" % PROBE_USER, "HTTP %s / %s" % (st, res))
    st, res = call("POST", "/auth/login", {"username": PROBE_USER, "password": PROBE_PASS})
    probe_token = res["data"]["token"]
    print("    探针用户已登录（该用户零数据）")

    st, res = ask(probe_token, q1)
    print("    HTTP %s" % st)
    if check(st == 200, "新用户提问 HTTP 200"):
        d4 = res["data"]
        leak_answer = d4["answer"]
        print("    答案:\n%s" % "\n".join("      | " + ln for ln in leak_answer.splitlines()))
        # demo 的关键数字 —— 一个都不该出现。
        # 只取 >= 2 位的数：像 "3"（今日任务数）这种个位数在正常中文里
        # 到处都会出现（「3 项」「第 3 天」），拿它当泄漏判据会误报。
        raw_numbers = [week_minutes, eng_week, o.get("todayPlannedMinutes"),
                       o.get("currentStreakDays"), o.get("longestStreakDays"),
                       o.get("totalCheckinDays"), o.get("daysUntilExam"),
                       o.get("totalActualMinutes"), o.get("totalTasks")]
        demo_numbers = [str(n) for n in raw_numbers if n is not None and int(n) >= 10]
        leaked = sorted({n for n in demo_numbers if n in leak_answer})
        check(not leaked, "答案里没有 demo 的任何关键数字（零泄漏）",
              "泄漏了: %s" % leaked)
        check("没有" in leak_answer or "还没" in leak_answer or "暂无" in leak_answer,
              "新用户得到的是「没有数据」类的回答，而不是别人的数据",
              "答案: %s" % leak_answer[:160])
        # 注意：**不能**断言 degraded=false。这个新用户确实没有「数学」这个科目，
        # 所以 getDailyMinutes 本来就该失败 —— degraded=true 才是诚实的标记。
        # 这里只断言「这个字段总是存在」，值本身交给上面那条「零泄漏」去把关。
        check(d4.get("degraded") in (True, False),
              "degraded 字段总是存在且是布尔值（实际 %s）" % d4.get("degraded"))

    # ---- 清理探针用户 ----
    print("\n--- 清理 ---")
    print("    探针用户 %s 已用 it_ 前缀，下次 `mvn verify` 会被 ExamTrackerIT 的" % PROBE_USER)
    print("    @AfterAll 顺手清掉；想立刻清可以执行：")
    print("      DELETE FROM app_user WHERE LEFT(username, 3) = 'it_';")

    print("\n" + "=" * 78)
    print(" 结果: %d 项通过, %d 项失败" % (passed, failed))
    print("=" * 78)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
