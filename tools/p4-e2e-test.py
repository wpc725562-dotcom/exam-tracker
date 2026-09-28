#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
p4-e2e-test.py —— exam-tracker（备考任务追踪 API）端到端验证

跑之前必须满足：
  1) MySQL 在跑（工作区便携版，127.0.0.1:3308）
  2) 应用在跑     java -jar target/exam-tracker-1.0.0.jar

用法：
  python tools/p4-e2e-test.py
  python tools/p4-e2e-test.py --base http://127.0.0.1:8090/api
  python tools/p4-e2e-test.py --log D:/java-workspace/logs/p4.log

为什么要有这个脚本：
  这个项目的接口「返回 200」远远不能说明它对。比如：
    · 越权访问别人的数据，如果只判断 200，会漏掉（应该 404）
    · 校验注解漏加，非法参数会被静默接受
    · 分页参数没转换，page=1 会变成第二页
  所以每个断言都检查**语义**，不只看状态码。

  ⚠️ 本机访问回环地址必须绕过系统代理，否则 curl / requests 会超时。
     本脚本用 http.client 直连（不走代理），等价于 curl --noproxy '*'。
"""

import argparse
import http.client
import json
import re
import sys
import time
from pathlib import Path
from urllib.parse import urlencode

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
BASE_HOST = "127.0.0.1"
BASE_PORT = 8090
CTX = "/api"

DEFAULT_LOG = Path("D:/java-workspace/logs/p4.log")

results = []


def record(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  —— {detail}" if detail else ""))


# ---------------------------------------------------------------------------
# HTTP 直连（绕过系统代理）
# ---------------------------------------------------------------------------
def request(method, path, body=None, token=None, timeout=30):
    """返回 (status, parsed_json_or_text)。"""
    conn = http.client.HTTPConnection(BASE_HOST, BASE_PORT, timeout=timeout)
    headers = {"Accept": "application/json"}
    payload = None
    if body is not None:
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        conn.request(method, CTX + path, body=payload, headers=headers)
        resp = conn.getresponse()
        raw = resp.read().decode("utf-8", errors="replace")
        try:
            return resp.status, json.loads(raw)
        except json.JSONDecodeError:
            return resp.status, raw
    finally:
        conn.close()


def request_follow(path, max_hops=3):
    """跟随最多 max_hops 次重定向。返回 (status, body, 最终路径)。

    为什么需要它：/doc.html 这类路径本身就是个重定向入口（302 到真正的 UI 页面），
    只取第一个响应会把「正常跳转」误判成「页面打不开」。
    """
    current = path
    for _ in range(max_hops):
        conn = http.client.HTTPConnection(BASE_HOST, BASE_PORT, timeout=20)
        try:
            conn.request("GET", CTX + current, headers={"Accept": "*/*"})
            resp = conn.getresponse()
            body = resp.read().decode("utf-8", errors="replace")
            if resp.status in (301, 302, 303, 307, 308):
                location = resp.getheader("Location") or ""
                # Location 可能带 context-path（/api/xxx），去掉后再拼，避免变成 /api/api/xxx
                current = location[len(CTX):] if location.startswith(CTX) else location
                continue
            return resp.status, body, current
        finally:
            conn.close()
    return 508, "", current


def get(path, token=None, **params):
    if params:
        path += "?" + urlencode({k: v for k, v in params.items() if v is not None})
    return request("GET", path, token=token)


def post(path, body=None, token=None):
    return request("POST", path, body=body, token=token)


def put(path, body=None, token=None):
    return request("PUT", path, body=body, token=token)


def patch(path, body=None, token=None):
    return request("PATCH", path, body=body, token=token)


def delete(path, token=None, **params):
    if params:
        path += "?" + urlencode({k: v for k, v in params.items() if v is not None})
    return request("DELETE", path, token=token)


def data_of(payload):
    """从统一响应体里取 data；不是预期结构时返回 None。"""
    return payload.get("data") if isinstance(payload, dict) else None


def code_of(payload):
    return payload.get("code") if isinstance(payload, dict) else None


# ---------------------------------------------------------------------------
# [1] 基础
# ---------------------------------------------------------------------------
def check_basic():
    print("\n[1] 基础")

    # 存活探针返回的是裸 JSON（不套 ApiResponse 外壳）——
    # 探针是给负载均衡器和容器编排看的，越简单越好，不需要业务码。
    status, body = get("/health")
    record("存活探针 /api/health", status == 200 and body == {"status": "ok"},
           f"HTTP {status} {body}")

    # /doc.html 会 302 到 /swagger-ui/index.html（Springdoc 的行为，浏览器会自动跟随）。
    # 所以这里要**跟随跳转**再判断，只看第一个响应会把正常的重定向当成故障。
    status, body, final_url = request_follow("/doc.html")
    record("接口文档可访问（/doc.html 跟随跳转后）",
           status == 200 and isinstance(body, str) and len(body) > 300,
           f"HTTP {status} -> {final_url}，{len(body) if isinstance(body, str) else 0} 字节")

    status, body = get("/v3/api-docs")
    paths = body.get("paths", {}) if isinstance(body, dict) else {}
    # 14 个路径：auth×3、health、subjects×2、tasks×3、checkins×3、stats×2
    record("OpenAPI 文档包含全部业务接口",
           status == 200 and len(paths) >= 14,
           f"HTTP {status}，{len(paths)} 个路径")

    # 未认证访问受保护接口：必须是 **JSON 的 401**，不能是重定向到登录页的 HTML
    status, body = get("/subjects")
    record("未带 token 访问受保护接口 -> JSON 401",
           status == 401 and code_of(body) == 40100,
           f"HTTP {status} code={code_of(body)}")

    # 伪造 token 也必须被挡住
    status, body = get("/subjects", token="not-a-real-token")
    record("伪造 token 被拒绝", status == 401, f"HTTP {status}")


# ---------------------------------------------------------------------------
# [2] 认证
# ---------------------------------------------------------------------------
def check_auth(username, password):
    print("\n[2] 认证")
    token_holder = {}

    status, body = post("/auth/register",
                        {"username": username, "password": password,
                         "nickname": "备考中的我", "examDate": "2027-03-14"})
    user = data_of(body) or {}
    record("注册成功返回 201", status == 201 and user.get("username") == username,
           f"HTTP {status} id={user.get('id')}")
    record("注册返回距考试天数（倒计时算对）", user.get("daysUntilExam") is not None,
           f"daysUntilExam={user.get('daysUntilExam')}")

    # 重名必须 409，而不是静默成功或 500
    status, body = post("/auth/register", {"username": username, "password": password})
    record("重复用户名 -> 409 CONFLICT", status == 409 and code_of(body) == 40900,
           f"HTTP {status} code={code_of(body)}")

    # 参数校验
    status, body = post("/auth/register", {"username": "a", "password": "123"})
    record("非法用户名/短密码 -> 400 且带字段级错误",
           status == 400 and isinstance(data_of(body), dict) and len(data_of(body)) >= 2,
           f"HTTP {status} fields={list((data_of(body) or {}).keys())}")

    # 登录
    status, body = post("/auth/login", {"username": username, "password": password})
    token = data_of(body).get("token") if status == 200 else None
    token_holder["token"] = token
    record("登录成功并拿到 JWT", status == 200 and token and len(token) > 60,
           f"HTTP {status}，token {len(token) if token else 0} 字符")

    # 密码错误：提示必须与「用户不存在」完全一致（防账号枚举）
    status_wrong_pw, body_wrong_pw = post("/auth/login", {"username": username, "password": "wrong-password"})
    status_no_user, body_no_user = post("/auth/login", {"username": "no-such-user-xyz", "password": "whatever"})
    same = (body_wrong_pw.get("message") == body_no_user.get("message")) if isinstance(body_wrong_pw, dict) else False
    record("密码错误与用户不存在的提示完全一致（防账号枚举）",
           status_wrong_pw == 401 and status_no_user == 401 and same,
           f"HTTP {status_wrong_pw}/{status_no_user}，message 相同={same}")

    status, body = get("/auth/me", token=token)
    record("带 token 可读取当前用户", status == 200 and data_of(body).get("username") == username,
           f"HTTP {status}")

    status, body = patch("/auth/me", {"nickname": "改过的昵称", "examDate": "2027-06-07"}, token=token)
    record("可修改个人资料", status == 200 and data_of(body).get("nickname") == "改过的昵称",
           f"HTTP {status} nickname={data_of(body).get('nickname') if isinstance(data_of(body), dict) else None}")

    return token_holder.get("token")


# ---------------------------------------------------------------------------
# [3] 科目
# ---------------------------------------------------------------------------
def check_subjects(token):
    print("\n[3] 科目")
    created = {}

    specs = [("语文", "#EF4444", 300), ("数学", "#4F46E5", 420),
             ("英语", "#10B981", 600), ("专业课", "#F59E0B", 480)]
    ids = []
    for name, color, target in specs:
        status, body = post("/subjects",
                            {"name": name, "color": color, "targetMinutesPerWeek": target}, token=token)
        sid = data_of(body).get("id") if status == 201 else None
        if sid:
            ids.append(sid)
        created[name] = sid
    record("批量创建 4 个科目", len(ids) == 4, f"ids={ids}")

    status, body = post("/subjects", {"name": "数学", "targetMinutesPerWeek": 100}, token=token)
    record("同科目重名 -> 409", status == 409, f"HTTP {status}")

    status, body = post("/subjects", {"name": "物理", "color": "not-a-color",
                                      "targetMinutesPerWeek": 100}, token=token)
    record("非法颜色 -> 400（@Pattern 生效）", status == 400, f"HTTP {status}")

    status, body = post("/subjects", {"name": "化学", "targetMinutesPerWeek": -5}, token=token)
    record("负的目标时长 -> 400（@Min 生效）", status == 400, f"HTTP {status}")

    status, body = get("/subjects", token=token)
    items = data_of(body) or []
    record("科目列表按 sortOrder 返回 4 个", status == 200 and len(items) == 4,
           f"HTTP {status}，{len(items)} 个")

    sid = created["数学"]
    status, body = put(f"/subjects/{sid}",
                       {"name": "数学", "color": "#6366F1", "targetMinutesPerWeek": 500, "sortOrder": 1},
                       token=token)
    record("修改科目（含颜色归一化）",
           status == 200 and data_of(body).get("targetMinutesPerWeek") == 500,
           f"HTTP {status} color={data_of(body).get('color') if isinstance(data_of(body), dict) else None}")

    # 不存在的科目 -> 404
    status, body = get("/subjects/99999999", token=token)
    record("访问不存在的科目 -> 404", status == 404 and code_of(body) == 40400, f"HTTP {status}")

    return created


# ---------------------------------------------------------------------------
# [4] 任务
# ---------------------------------------------------------------------------
def check_tasks(token, subjects):
    print("\n[4] 任务")
    math_id = subjects["数学"]
    english_id = subjects["英语"]

    today = time.strftime("%Y-%m-%d")
    yesterday = time.strftime("%Y-%m-%d", time.localtime(time.time() - 86400))

    task_ids = []
    for i in range(1, 6):
        status, body = post("/tasks", {
            "subjectId": math_id,
            "title": f"做一套真题（第 {i} 套）",
            "planDate": today if i <= 3 else yesterday,
            "planMinutes": 60 + i * 10,
            "priority": "HIGH" if i == 1 else "MEDIUM",
            "note": "错题整理到错题本",
        }, token=token)
        if status == 201:
            task_ids.append(data_of(body).get("id"))
    record("批量创建 5 个任务", len(task_ids) == 5, f"ids={task_ids}")

    # 科目必须存在且属于自己
    status, body = post("/tasks", {"subjectId": 99999999, "title": "挂到不存在的科目",
                                   "planDate": today, "planMinutes": 30}, token=token)
    record("挂到不存在的科目 -> 404", status == 404, f"HTTP {status}")

    status, body = post("/tasks", {"subjectId": math_id, "title": "", "planDate": today,
                                   "planMinutes": 0}, token=token)
    record("空标题 / 0 分钟 -> 400", status == 400, f"HTTP {status}")

    # 分页
    status, body = get("/tasks", token=token, page=1, size=2)
    d = data_of(body) or {}
    record("分页 size=2 只返回 2 条，且 total=5",
           status == 200 and len(d.get("items", [])) == 2 and d.get("total") == 5,
           f"HTTP {status} items={len(d.get('items', []))} total={d.get('total')} pages={d.get('totalPages')}")
    record("分页页码从 1 开始（page=1 是第一页）",
           d.get("page") == 1, f"page={d.get('page')}")

    status, body = get("/tasks", token=token, page=3, size=2)
    d3 = data_of(body) or {}
    record("第 3 页只有 1 条（总数 5）", len(d3.get("items", [])) == 1,
           f"items={len(d3.get('items', []))}")

    # 翻页不重不漏
    seen = []
    for p in (1, 2, 3):
        _, b = get("/tasks", token=token, page=p, size=2)
        seen += [t["id"] for t in (data_of(b) or {}).get("items", [])]
    record("三页拼起来不重不漏（排序稳定）",
           len(seen) == 5 and len(set(seen)) == 5, f"共 {len(seen)} 条，去重后 {len(set(seen))} 条")

    # 筛选
    status, body = get("/tasks", token=token, from_=today, to=today)
    status2, body2 = get("/tasks", token=token, **{"from": today, "to": today})
    n_today = (data_of(body2) or {}).get("total")
    record("按日期筛选（from=to=今天）只返回今天 3 条", n_today == 3, f"total={n_today}")

    status, body = get("/tasks", token=token, subjectId=english_id)
    record("按科目筛选（英语下暂无任务）返回 0 条",
           (data_of(body) or {}).get("total") == 0,
           f"total={(data_of(body) or {}).get('total')}")

    status, body = get("/tasks", token=token, keyword="真题")
    record("关键词模糊匹配命中 5 条", (data_of(body) or {}).get("total") == 5,
           f"total={(data_of(body) or {}).get('total')}")

    # 关键词里的 % 必须被转义，不能变成「匹配全部」
    status, body = get("/tasks", token=token, keyword="%")
    record("关键词含 % 被转义（不是匹配全部）",
           (data_of(body) or {}).get("total") == 0,
           f"total={(data_of(body) or {}).get('total')}")

    # 排序白名单
    status, body = get("/tasks", token=token, sortBy="title")
    record("非白名单排序字段 -> 400（防注入）", status == 400, f"HTTP {status}")

    status, body = get("/tasks", token=token, sortBy="planMinutes", direction="desc")
    mins = [t["planMinutes"] for t in (data_of(body) or {}).get("items", [])]
    record("按 planMinutes 倒序排序生效",
           status == 200 and mins == sorted(mins, reverse=True), f"{mins}")

    # 状态流转
    tid = task_ids[0]
    status, body = patch(f"/tasks/{tid}/status", {"status": "DONE"}, token=token)
    d = data_of(body) or {}
    record("标记完成会写入 completedAt", status == 200 and d.get("completedAt") is not None,
           f"HTTP {status} completedAt={d.get('completedAt')}")

    status, body = patch(f"/tasks/{tid}/status", {"status": "TODO"}, token=token)
    d = data_of(body) or {}
    record("改回 TODO 会清空 completedAt（状态与时间始终一致）",
           status == 200 and d.get("completedAt") is None, f"completedAt={d.get('completedAt')}")

    status, body = patch(f"/tasks/{tid}/status", {"status": "SKIPPED"}, token=token)
    record("可标记为 SKIPPED", status == 200 and (data_of(body) or {}).get("status") == "SKIPPED",
           f"HTTP {status}")

    status, body = patch(f"/tasks/{tid}/status", {"status": "NOT_A_STATUS"}, token=token)
    record("非法枚举值 -> 400", status == 400, f"HTTP {status}")

    # 超大分页参数
    status, body = get("/tasks", token=token, size=99999)
    record("size 超过上限 -> 400（挡住拖库请求）", status == 400, f"HTTP {status}")

    return task_ids


# ---------------------------------------------------------------------------
# [5] 打卡
# ---------------------------------------------------------------------------
def check_checkins(token, subjects, task_ids):
    print("\n[5] 打卡")
    math_id = subjects["数学"]
    english_id = subjects["英语"]
    today = time.strftime("%Y-%m-%d")
    yesterday = time.strftime("%Y-%m-%d", time.localtime(time.time() - 86400))
    two_days_ago = time.strftime("%Y-%m-%d", time.localtime(time.time() - 2 * 86400))

    # 1) 挂在任务上：科目应从任务推导出来
    status, body = post("/checkins", {"taskId": task_ids[0], "actualMinutes": 75,
                                      "note": "比预想慢"}, token=token)
    d = data_of(body) or {}
    record("打卡可挂在任务上，且科目从任务推导",
           status == 201 and d.get("subjectId") == math_id and d.get("taskId") == task_ids[0],
           f"HTTP {status} subjectId={d.get('subjectId')}（数学={math_id}）")

    # 传了矛盾的 subjectId 也会被任务覆盖 —— 非法状态在结构上无法表达
    status, body = post("/checkins", {"taskId": task_ids[1], "subjectId": english_id,
                                      "actualMinutes": 30}, token=token)
    record("subjectId 与任务矛盾时以任务为准（不产生矛盾数据）",
           status == 201 and (data_of(body) or {}).get("subjectId") == math_id,
           f"subjectId={(data_of(body) or {}).get('subjectId')}")

    # 2) 不挂任务：只传 subjectId
    status, body = post("/checkins", {"subjectId": english_id, "actualMinutes": 45,
                                      "checkinDate": today, "note": "听听力"}, token=token)
    record("打卡可不挂任务（只传 subjectId）",
           status == 201 and (data_of(body) or {}).get("taskId") is None,
           f"HTTP {status} taskId={(data_of(body) or {}).get('taskId')}")

    # 3) 两个都不传 -> 400
    status, body = post("/checkins", {"actualMinutes": 10}, token=token)
    record("subjectId 和 taskId 都不传 -> 400", status == 400, f"HTTP {status}")

    # 4) 未来日期
    future = time.strftime("%Y-%m-%d", time.localtime(time.time() + 3 * 86400))
    status, body = post("/checkins", {"subjectId": math_id, "actualMinutes": 30,
                                      "checkinDate": future}, token=token)
    record("未来日期打卡 -> 400", status == 400, f"HTTP {status}")

    # 5) 别人的任务 id
    status, body = post("/checkins", {"taskId": 99999999, "actualMinutes": 30}, token=token)
    record("打卡挂到不存在的任务 -> 404", status == 404, f"HTTP {status}")

    # 6) 造几天连续打卡，供统计验证
    for i, day in enumerate([yesterday, two_days_ago]):
        post("/checkins", {"subjectId": math_id, "actualMinutes": 60 + i * 15,
                           "checkinDate": day}, token=token)

    status, body = get("/checkins", token=token)
    d = data_of(body) or {}
    record("打卡列表可查询", status == 200 and d.get("total", 0) >= 4,
           f"HTTP {status} total={d.get('total')}")

    status, body = get("/checkins/daily", token=token)
    daily = data_of(body) or []
    record("按天汇总返回日历数据", status == 200 and len(daily) >= 3,
           f"HTTP {status}，{len(daily)} 天有记录")

    # 默认窗口：不传日期时不应返回全部历史
    status, body = get("/checkins", token=token)
    record("打卡列表默认窗口（不传日期不会拉全表）", status == 200, f"HTTP {status}")


# ---------------------------------------------------------------------------
# [6] 统计
# ---------------------------------------------------------------------------
def check_stats(token, subjects):
    print("\n[6] 统计")

    status, body = get("/stats/overview", token=token)
    d = data_of(body) or {}
    record("仪表盘总览可访问", status == 200, f"HTTP {status}")
    record("今日任务数 = 3", d.get("todayTotalTasks") == 3, f"todayTotalTasks={d.get('todayTotalTasks')}")
    record("今日实际投入 > 0（来自打卡）", (d.get("todayActualMinutes") or 0) > 0,
           f"todayActualMinutes={d.get('todayActualMinutes')}")
    record("连续打卡天数 = 3（今天 + 昨天 + 前天）",
           d.get("currentStreakDays") == 3, f"currentStreakDays={d.get('currentStreakDays')}")
    record("历史最长连续 >= 当前连续",
           (d.get("longestStreakDays") or 0) >= (d.get("currentStreakDays") or 0),
           f"longest={d.get('longestStreakDays')} current={d.get('currentStreakDays')}")
    record("考试倒计时已计算", d.get("daysUntilExam") is not None,
           f"examDate={d.get('examDate')} days={d.get('daysUntilExam')}")

    status, body = get("/stats/subjects", token=token, days=7)
    d = data_of(body) or {}
    rows = d.get("subjects") or []
    record("四科看板返回 4 行", status == 200 and len(rows) == 4, f"HTTP {status}，{len(rows)} 行")
    record("看板回显统计窗口天数", d.get("periodDays") == 7, f"periodDays={d.get('periodDays')}")

    math_row = next((r for r in rows if r["id"] == subjects["数学"]), None)
    record("数学科目：5 个任务、窗口内有实际时长",
           math_row is not None and math_row.get("taskTotal") == 5 and math_row.get("periodMinutes", 0) > 0,
           f"taskTotal={math_row.get('taskTotal') if math_row else None} "
           f"periodMinutes={math_row.get('periodMinutes') if math_row else None}")

    english_row = next((r for r in rows if r["id"] == subjects["英语"]), None)
    record("英语科目：0 个任务但窗口内有打卡",
           english_row is not None and english_row.get("taskTotal") == 0
           and english_row.get("periodMinutes", 0) > 0,
           f"taskTotal={english_row.get('taskTotal') if english_row else None} "
           f"periodMinutes={english_row.get('periodMinutes') if english_row else None}")

    record("看板目标时长按窗口折算（周目标 × days/7）",
           math_row is not None and math_row.get("targetMinutesInPeriod") == 500,
           f"周目标 500 × 7/7 = {math_row.get('targetMinutesInPeriod') if math_row else None}")

    status, body = get("/stats/subjects", token=token, days=99999)
    record("统计天数超上限 -> 400", status == 400, f"HTTP {status}")


# ---------------------------------------------------------------------------
# [7] 数据隔离
# ---------------------------------------------------------------------------
def check_isolation(token_a, subjects, task_ids):
    print("\n[7] 数据隔离（越权防护）")
    other = "other_" + str(int(time.time()))

    status, body = post("/auth/register", {"username": other, "password": "otherpass123"})
    record("第二个用户可以注册", status == 201, f"HTTP {status}")

    status, body = post("/auth/login", {"username": other, "password": "otherpass123"})
    token_b = data_of(body).get("token") if status == 200 else None
    record("第二个用户可以登录", token_b is not None, f"HTTP {status}")

    status, body = get("/subjects", token=token_b)
    record("B 看不到 A 的科目", status == 200 and len(data_of(body) or []) == 0,
           f"B 的科目数={len(data_of(body) or [])}")

    status, body = get("/tasks", token=token_b)
    record("B 看不到 A 的任务", status == 200 and (data_of(body) or {}).get("total") == 0,
           f"B 的任务数={(data_of(body) or {}).get('total')}")

    # 直接按 id 访问 A 的资源 —— 这是最典型的越权方式（IDOR）
    status, body = get(f"/subjects/{subjects['数学']}", token=token_b)
    record("B 按 id 直接读 A 的科目 -> 404（不是 403，避免暴露资源是否存在）",
           status == 404, f"HTTP {status}")

    status, body = get(f"/tasks/{task_ids[0]}", token=token_b)
    record("B 按 id 直接读 A 的任务 -> 404", status == 404, f"HTTP {status}")

    status, body = put(f"/tasks/{task_ids[0]}",
                       {"subjectId": subjects["数学"], "title": "篡改", "planDate": "2026-09-28",
                        "planMinutes": 1}, token=token_b)
    record("B 不能改 A 的任务 -> 404", status == 404, f"HTTP {status}")

    status, body = delete(f"/tasks/{task_ids[0]}", token=token_b)
    record("B 不能删 A 的任务 -> 404", status == 404, f"HTTP {status}")

    # A 的数据必须原封不动
    status, body = get(f"/tasks/{task_ids[0]}", token=token_a)
    record("A 的任务未被 B 影响", status == 200 and (data_of(body) or {}).get("title") != "篡改",
           f"title={(data_of(body) or {}).get('title')}")

    status, body = get("/stats/overview", token=token_b)
    d = data_of(body) or {}
    record("B 的统计全为 0（没有串到 A 的数据）",
           d.get("totalTasks") == 0 and d.get("totalActualMinutes") == 0,
           f"totalTasks={d.get('totalTasks')} totalActualMinutes={d.get('totalActualMinutes')}")


# ---------------------------------------------------------------------------
# [8] 删除保护
# ---------------------------------------------------------------------------
def check_delete_guards(token, subjects):
    print("\n[8] 删除保护")
    sid = subjects["语文"]

    # 先给这个科目造一条数据
    status, body = post("/checkins", {"subjectId": sid, "actualMinutes": 20}, token=token)
    record("为「语文」造一条打卡数据", status == 201, f"HTTP {status}")

    status, body = delete(f"/subjects/{sid}", token=token)
    record("删除有数据的科目 -> 409 并提示数量",
           status == 409 and "force" in str(body.get("message", "")),
           f"HTTP {status} message={body.get('message') if isinstance(body, dict) else body}")

    # force=true 才真的删
    status, body = delete(f"/subjects/{sid}", token=token, force="true")
    record("force=true 才允许级联删除", status == 200, f"HTTP {status}")

    status, body = get(f"/subjects/{sid}", token=token)
    record("科目确已删除", status == 404, f"HTTP {status}")


# ---------------------------------------------------------------------------
# [9] 日志体检
# ---------------------------------------------------------------------------
def check_log(log_path):
    print(f"\n[9] 日志体检  {log_path}")
    p = Path(log_path)
    if not p.exists():
        record("日志文件存在", False, f"找不到 {log_path}")
        return
    text = p.read_text(encoding="utf-8", errors="replace")
    record("日志文件存在", True, f"{len(text)} 字符")

    must_have = [
        ("应用已启动", r"Started ExamTrackerApplication"),
        ("端口正确 8090", r"Tomcat started on port 8090"),
        ("context-path = /api", r"context path '/api'"),
        ("表结构校验通过（ddl-auto=validate）", r"Hibernate ORM core version"),
    ]
    for name, pattern in must_have:
        record(name, re.search(pattern, text) is not None)

    must_not_have = [
        ("无 SchemaManagementException（表与实体不匹配）", r"SchemaManagementException"),
        ("无 APPLICATION FAILED TO START", r"APPLICATION FAILED TO START"),
        ("无 UnsatisfiedDependencyException", r"UnsatisfiedDependencyException"),
        ("无 NullPointerException", r"NullPointerException"),
        ("无 SQLSyntaxErrorException", r"SQLSyntaxErrorException"),
        ("无 LazyInitializationException", r"LazyInitializationException"),
        ("无 DuplicateKeyException（唯一索引未被误触发）", r"DuplicateKeyException"),
    ]
    for name, pattern in must_not_have:
        record(name, re.search(pattern, text) is None)

    # 真实的 ERROR 行（排除客户端断连噪声）
    noise = ("ClientAbortException", "中止了一个已建立的连接", "AsyncRequestNotUsableException",
             "Connection reset by peer")
    real_errors = [line for line in text.splitlines()
                   if re.search(r"\bERROR\b", line) and not any(n in line for n in noise)]
    record("无真实 ERROR 行", len(real_errors) == 0,
           f"{len(real_errors)} 行" + (f"：{real_errors[0][:120]}" if real_errors else ""))


# ---------------------------------------------------------------------------
def main():
    global BASE_HOST, BASE_PORT
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=f"http://{BASE_HOST}:{BASE_PORT}{CTX}")
    ap.add_argument("--log", default=str(DEFAULT_LOG))
    args = ap.parse_args()

    m = re.match(r"http://([^:/]+):(\d+)", args.base)
    if m:
        BASE_HOST, BASE_PORT = m.group(1), int(m.group(2))

    print("=" * 72)
    print("exam-tracker 端到端验证")
    print("=" * 72)
    print(f"目标: http://{BASE_HOST}:{BASE_PORT}{CTX}")

    # 每次跑用不同用户名，脚本可以反复执行
    stamp = str(int(time.time()))[-8:]
    user_a = f"darling{stamp}"
    password = "study2026"

    try:
        check_basic()
        token = check_auth(user_a, password)
        if not token:
            print("\n!! 拿不到 token，后续用例无法继续")
        else:
            subjects = check_subjects(token)
            task_ids = check_tasks(token, subjects)
            check_checkins(token, subjects, task_ids)
            check_stats(token, subjects)
            check_isolation(token, subjects, task_ids)
            check_delete_guards(token, subjects)
        check_log(args.log)
    except Exception as exc:  # noqa: BLE001
        import traceback
        traceback.print_exc()
        record("脚本执行未抛异常", False, f"{type(exc).__name__}: {exc}")

    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print("\n" + "=" * 72)
    print(f"结果：{passed}/{total} PASS")
    print("=" * 72)
    if passed != total:
        print("\n失败项：")
        for name, ok, detail in results:
            if not ok:
                print(f"  - {name}  {detail}")
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
