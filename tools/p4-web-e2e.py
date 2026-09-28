#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
前端契约验证 —— 检查 app.js 依赖的每一个字段，后端是否真的返回了。

为什么单独写这个而不是复用 tools/p4-e2e-test.py：
那份脚本验的是「后端行为对不对」（业务语义、边界、越权）；
这份验的是「前端会不会白屏」。两者的失败模式完全不同 ——
后端可能一切正常，但少返回一个 `subjectName`，前端那一行就渲染成 undefined。
而「字段悄悄少了一个」恰恰是重构时最容易漏、又最难靠肉眼发现的问题。

★ 用 http.client 直连，不走 requests / curl。
  本机有 CODEBUDDY_SERVICE_PROXY_URL 指向回环，系统代理会把 127.0.0.1 也劫持掉，
  用 curl 不加 --noproxy 会得到「服务明明在跑却连不上」的假故障。

用法：
    python tools/p4-web-e2e.py            # 默认 http://127.0.0.1:8090
    python tools/p4-web-e2e.py 8091
"""
import http.client
import json
import sys

HOST = "127.0.0.1"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8090
BASE = "/api"

DEMO_USER = "demo"
DEMO_PASS = "demo123456"

passed = 0
failed = 0


def check(label, condition, detail=""):
    global passed, failed
    if condition:
        passed += 1
        print("  [PASS] %s" % label)
    else:
        failed += 1
        print("  [FAIL] %s   %s" % (label, detail))


def call(method, path, body=None, token=None, raw=False):
    """返回 (status, parsed_or_bytes)。"""
    conn = http.client.HTTPConnection(HOST, PORT, timeout=15)
    headers = {"Accept": "application/json"}
    payload = None
    if body is not None:
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    conn.request(method, BASE + path, body=payload, headers=headers)
    res = conn.getresponse()
    data = res.read()
    status = res.status
    conn.close()
    if raw:
        return status, data
    try:
        return status, json.loads(data.decode("utf-8"))
    except Exception:
        return status, data


def data_of(resp):
    """从 {code,message,data} 外壳里取出 data。"""
    if isinstance(resp, dict) and "data" in resp:
        return resp["data"]
    return None


print("=" * 68)
print(" 前端契约验证  ->  http://%s:%d%s/" % (HOST, PORT, BASE))
print("=" * 68)

# ---------------------------------------------------------------- 1. 静态资源 ---
print("\n[1] 静态资源（登录页必须能匿名打开）")
for path, expect_type in [("/", "text/html"),
                          ("/index.html", "text/html"),
                          ("/app.js", "javascript"),
                          ("/style.css", "css")]:
    conn = http.client.HTTPConnection(HOST, PORT, timeout=10)
    conn.request("GET", BASE + path)
    res = conn.getresponse()
    ctype = res.getheader("Content-Type") or ""
    size = len(res.read())
    conn.close()
    check("GET %-14s 200 + %s" % (path, expect_type),
          res.status == 200 and expect_type in ctype and size > 0,
          "status=%s type=%s size=%d" % (res.status, ctype, size))

# ------------------------------------------------------------------- 2. 认证 ---
print("\n[2] 认证")
status, resp = call("POST", "/auth/login", {"username": DEMO_USER, "password": DEMO_PASS})
check("登录返回 200", status == 200, "status=%s body=%s" % (status, resp))
login = data_of(resp) or {}
check("data.token 存在且非空", bool(login.get("token")))
check("data.tokenType == 'Bearer'", login.get("tokenType") == "Bearer", repr(login.get("tokenType")))
check("data.expiresIn 是正数", isinstance(login.get("expiresIn"), int) and login["expiresIn"] > 0)
user = login.get("user") or {}
check("data.user.id 存在", user.get("id") is not None)
check("data.user.username == demo", user.get("username") == DEMO_USER)
token = login.get("token")

status, resp = call("POST", "/auth/login", {"username": DEMO_USER, "password": "wrong-password"})
check("错误密码返回 401", status == 401, "status=%s" % status)

status, resp = call("GET", "/auth/me", token=token)
me = data_of(resp) or {}
check("GET /auth/me 返回 username", me.get("username") == DEMO_USER)
check("GET /auth/me 返回 daysUntilExam（倒计时卡片要用）",
      "daysUntilExam" in me, "keys=%s" % list(me.keys()))

# ------------------------------------------------------------------- 3. 科目 ---
print("\n[3] 科目（筛选下拉 + 看板颜色）")
status, resp = call("GET", "/subjects", token=token)
subjects = data_of(resp) or []
check("返回 4 个科目", len(subjects) == 4, "got %d" % len(subjects))
if subjects:
    s0 = subjects[0]
    for f in ("id", "name", "color", "targetMinutesPerWeek", "sortOrder"):
        check("科目字段 %s 存在" % f, f in s0, "keys=%s" % list(s0.keys()))
    check("color 是 #RRGGBB 形式",
          isinstance(s0.get("color"), str) and s0["color"].startswith("#") and len(s0["color"]) == 7,
          repr(s0.get("color")))

# ------------------------------------------------------------------- 4. 任务 ---
print("\n[4] 任务（列表渲染依赖的字段）")
status, resp = call("GET", "/tasks?page=1&size=50&sortBy=planDate&direction=desc", token=token)
page = data_of(resp) or {}
check("分页外壳字段齐全",
      all(k in page for k in ("items", "page", "size", "total", "totalPages")),
      "keys=%s" % list(page.keys()))
items = page.get("items") or []
check("返回了任务", len(items) > 0, "got %d" % len(items))
if items:
    t0 = items[0]
    # 这些是 app.js 里直接拼进 HTML 的字段，少一个就会渲染成 undefined
    for f in ("id", "subjectId", "subjectName", "title", "planDate",
              "planMinutes", "priority", "status"):
        check("任务字段 %s 存在" % f, f in t0, "keys=%s" % list(t0.keys()))
    check("subjectName 有值（列表要显示科目名，不是 id）",
          bool(t0.get("subjectName")), repr(t0.get("subjectName")))
    check("priority 是 HIGH/MEDIUM/LOW 之一",
          t0.get("priority") in ("HIGH", "MEDIUM", "LOW"), repr(t0.get("priority")))
    check("status 是 TODO/DONE/SKIPPED 之一",
          t0.get("status") in ("TODO", "DONE", "SKIPPED"), repr(t0.get("status")))

status, resp = call("GET", "/tasks?page=1&size=50&status=DONE", token=token)
check("按状态筛选生效（全部是 DONE）",
      all(t.get("status") == "DONE" for t in (data_of(resp) or {}).get("items", [])))

# ------------------------------------------------------------------- 5. 统计 ---
print("\n[5] 仪表盘总览（四个卡片）")
status, resp = call("GET", "/stats/overview", token=token)
ov = data_of(resp) or {}
today_fields = ("todayTotalTasks", "todayDoneTasks", "todayPendingTasks",
                "todaySkippedTasks", "todayCompletionRate",
                "todayPlannedMinutes", "todayActualMinutes")
streak_fields = ("currentStreakDays", "longestStreakDays", "totalCheckinDays", "lastCheckinDate")
total_fields = ("totalTasks", "totalDoneTasks", "totalActualMinutes")
for f in today_fields + streak_fields + total_fields:
    check("overview.%s 存在" % f, f in ov, "keys=%s" % list(ov.keys()))
check("currentStreakDays > 0（连续打卡卡片不该是 0）",
      (ov.get("currentStreakDays") or 0) > 0, repr(ov.get("currentStreakDays")))
check("longestStreakDays >= currentStreakDays",
      (ov.get("longestStreakDays") or 0) >= (ov.get("currentStreakDays") or 0),
      "longest=%s current=%s" % (ov.get("longestStreakDays"), ov.get("currentStreakDays")))
check("totalActualMinutes > 0", (ov.get("totalActualMinutes") or 0) > 0)

print("\n[6] 四科看板")
status, resp = call("GET", "/stats/subjects?days=7", token=token)
board = data_of(resp) or {}
for f in ("periodDays", "from", "to", "periodTotalMinutes",
          "periodTargetMinutes", "overallAchievementRate", "subjects"):
    check("board.%s 存在" % f, f in board, "keys=%s" % list(board.keys()))
board_subjects = board.get("subjects") or []
check("看板返回 4 行", len(board_subjects) == 4, "got %d" % len(board_subjects))
if board_subjects:
    b0 = board_subjects[0]
    for f in ("id", "name", "color", "targetMinutesPerWeek", "taskTotal", "taskDone",
              "taskPending", "completionRate", "periodMinutes", "targetMinutesInPeriod",
              "achievementRate", "lastCheckinDate"):
        check("看板行字段 %s 存在" % f, f in b0, "keys=%s" % list(b0.keys()))

print("\n[7] 打卡趋势（柱状图数据源）")
# 前端请求的是「最近 14 天」，这里照抄同样的区间
import datetime
today = datetime.date.today()
frm = (today - datetime.timedelta(days=13)).isoformat()
to = today.isoformat()
status, resp = call("GET", "/checkins/daily?from=%s&to=%s" % (frm, to), token=token)
daily = data_of(resp) or []
check("返回按天汇总的数组", isinstance(daily, list), type(daily).__name__)
check("至少有 1 天有记录", len(daily) > 0, "got %d" % len(daily))
if daily:
    d0 = daily[0]
    check("每条含 date", "date" in d0, "keys=%s" % list(d0.keys()))
    check("每条含 minutes", "minutes" in d0, "keys=%s" % list(d0.keys()))
    check("日期都在请求区间内",
          all(frm <= d["date"] <= to for d in daily),
          "range=%s..%s" % (frm, to))

# ------------------------------------------------------- 8. 写操作（改回原状）---
print("\n[8] 写操作（状态流转 + 复原）")
status, resp = call("GET", "/tasks?page=1&size=1&status=TODO", token=token)
todo_items = (data_of(resp) or {}).get("items") or []
if todo_items:
    tid = todo_items[0]["id"]
    status, resp = call("PATCH", "/tasks/%d/status" % tid, {"status": "DONE"}, token=token)
    check("PATCH 标记完成返回 200", status == 200, "status=%s" % status)
    check("返回体里 status == DONE", (data_of(resp) or {}).get("status") == "DONE")
    check("completedAt 被写上", (data_of(resp) or {}).get("completedAt") is not None)

    status, resp = call("PATCH", "/tasks/%d/status" % tid, {"status": "TODO"}, token=token)
    check("改回 TODO 后 completedAt 被清空",
          (data_of(resp) or {}).get("completedAt") is None)
else:
    check("存在 TODO 任务可供测试", False, "演示数据里应该有 TODO")

status, resp = call("PATCH", "/tasks/999999/status", {"status": "DONE"}, token=token)
check("改不存在的任务返回 404", status == 404, "status=%s" % status)

status, resp = call("POST", "/tasks", {"subjectId": 1, "title": "x", "planDate": today.isoformat(),
                                       "planMinutes": 0}, token=token)
check("非法计划时长（0）返回 400", status == 400, "status=%s" % status)

print("\n" + "=" * 68)
print(" 结果：%d 通过 / %d 失败" % (passed, failed))
print("=" * 68)
sys.exit(1 if failed else 0)
