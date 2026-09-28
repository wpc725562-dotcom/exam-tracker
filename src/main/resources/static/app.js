/* =============================================================================
   备考任务追踪 —— 前端逻辑
   -----------------------------------------------------------------------------
   设计取舍（写在这里，因为面试时这段代码本身就是被提问的对象）：

   1. **零依赖、零构建。** 没有 React / Vue，没有 Vite，没有 node_modules。
      一个现代前端工程的 node_modules 动辄两万个文件，而这个页面的全部逻辑
      不到 800 行 —— 为它养一套构建链不划算，而且会让「clone 下来就能跑」
      变成「先 npm install 五分钟」。这里 clone → 起 jar → 打开浏览器，中间没有第三步。

   2. **图表是手写的 SVG，不引 Chart.js。** 引 CDN 意味着**断网就打不开**，
      而演示场景（面试、答辩、给同学看）经常没有稳定网络。
      柱状图的计算量就是「按最大值归一化高度」，手写比引库更短。

   3. **登录态放 localStorage。** 单页演示，不做 refresh token ——
      这一点在 README 的「已知限制」里写明了，不是漏掉的。
   ============================================================================= */

(function () {
    'use strict';

    /* ------------------------------------------------------------------ 配置 --- */

    // 页面本身就由 Spring Boot 托管在 /api/ 下，所以接口用绝对路径 /api。
    // 写成绝对路径而不是相对路径，是为了让页面即使被放到 /api/ 以外的路径也能用。
    var API = '/api';

    var TOKEN_KEY = 'exam-tracker.token';
    var USER_KEY = 'exam-tracker.user';

    /* ------------------------------------------------------------------ 状态 --- */

    var state = {
        token: localStorage.getItem(TOKEN_KEY) || '',
        user: null,
        subjects: [],
        statusFilter: '',
        subjectFilter: '',
        authMode: 'login', // 'login' | 'register'
        // AI 助手：可用性由后端 /ai/status 决定（没有配 key 时后端也是正常启动的）
        aiEnabled: false,
        // 当前对话 id。第一次提问时留空，服务端会生成一个并返回，之后原样带上即可续聊。
        aiConversationId: ''
    };

    /* --------------------------------------------------------------- 小工具 --- */

    function $(id) { return document.getElementById(id); }

    function esc(s) {
        if (s === null || s === undefined) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function todayStr() {
        var d = new Date();
        var m = String(d.getMonth() + 1).padStart(2, '0');
        var day = String(d.getDate()).padStart(2, '0');
        return d.getFullYear() + '-' + m + '-' + day;
    }

    function shiftDays(dateStr, delta) {
        var p = dateStr.split('-');
        var d = new Date(Number(p[0]), Number(p[1]) - 1, Number(p[2]));
        d.setDate(d.getDate() + delta);
        var m = String(d.getMonth() + 1).padStart(2, '0');
        var day = String(d.getDate()).padStart(2, '0');
        return d.getFullYear() + '-' + m + '-' + day;
    }

    function minutesToHours(min) {
        return (min / 60).toFixed(1);
    }

    function pct(rate) {
        return Math.round((rate || 0) * 100) + '%';
    }

    var toastTimer = null;
    function toast(msg, isError) {
        var el = $('toast');
        el.textContent = msg;
        el.className = 'show' + (isError ? ' error' : '');
        clearTimeout(toastTimer);
        toastTimer = setTimeout(function () { el.className = ''; }, 2600);
    }

    /* ------------------------------------------------------------- API 封装 --- */

    /**
     * 统一请求封装。
     *
     * 后端所有接口都返回 {code, message, data} 这层外壳，**业务失败时 HTTP 状态码也不是 200**
     * （这是刻意的：让监控和网关能看见真实故障）。所以这里两个都要判：
     *   · HTTP 非 2xx → 抛错
     *   · code !== 0  → 抛错
     * 401 单独处理：清掉本地登录态并回到登录页，而不是让用户对着一个空白页面猜。
     */
    function api(path, options) {
        options = options || {};
        var headers = { 'Accept': 'application/json' };
        if (options.body !== undefined) headers['Content-Type'] = 'application/json';
        if (state.token) headers['Authorization'] = 'Bearer ' + state.token;

        return fetch(API + path, {
            method: options.method || 'GET',
            headers: headers,
            body: options.body === undefined ? undefined : JSON.stringify(options.body)
        }).then(function (res) {
            return res.text().then(function (text) {
                var body = null;
                try { body = text ? JSON.parse(text) : null; } catch (e) { /* 非 JSON，下面统一报错 */ }

                if (res.status === 401) {
                    doLogout(true);
                    throw new Error('登录已过期，请重新登录');
                }
                if (!res.ok) {
                    throw new Error((body && body.message) || ('请求失败（HTTP ' + res.status + '）'));
                }
                if (!body || body.code !== 0) {
                    throw new Error((body && body.message) || '服务返回了预期之外的结构');
                }
                return body.data;
            });
        });
    }

    /* ------------------------------------------------------------------ 认证 --- */

    function setAuthMode(mode) {
        state.authMode = mode;
        var isLogin = mode === 'login';
        $('auth-title').textContent = isLogin ? '登录' : '注册';
        $('auth-sub').textContent = isLogin ? '备考任务追踪 · 记录每天的进度' : '创建一个账号开始记录';
        $('auth-submit').textContent = isLogin ? '登录' : '注册并登录';
        $('auth-switch-text').textContent = isLogin ? '还没有账号？' : '已经有账号了？';
        $('auth-switch').textContent = isLogin ? '立即注册' : '去登录';
        $('auth-nickname-field').classList.toggle('hidden', isLogin);
        $('auth-examdate-field').classList.toggle('hidden', isLogin);
        $('auth-password').setAttribute('autocomplete', isLogin ? 'current-password' : 'new-password');
    }

    function handleAuthSubmit(ev) {
        ev.preventDefault();
        var username = $('auth-username').value.trim();
        var password = $('auth-password').value;
        var btn = $('auth-submit');
        btn.disabled = true;

        var path, payload;
        if (state.authMode === 'login') {
            path = '/auth/login';
            payload = { username: username, password: password };
        } else {
            path = '/auth/register';
            payload = { username: username, password: password };
            var nick = $('auth-nickname').value.trim();
            var exam = $('auth-examdate').value;
            if (nick) payload.nickname = nick;
            if (exam) payload.examDate = exam;
        }

        api(path, { method: 'POST', body: payload })
            .then(function (data) {
                if (state.authMode === 'register') {
                    // 注册接口只返回用户信息，不返回 token —— 注册完直接拿同一套凭据登录
                    toast('注册成功，正在登录…');
                    return api('/auth/login', { method: 'POST', body: { username: username, password: password } });
                }
                return data;
            })
            .then(function (data) {
                state.token = data.token;
                state.user = data.user;
                localStorage.setItem(TOKEN_KEY, state.token);
                localStorage.setItem(USER_KEY, JSON.stringify(state.user));
                enterApp();
            })
            .catch(function (err) {
                toast(err.message, true);
            })
            .finally(function () {
                btn.disabled = false;
            });
    }

    function doLogout(silent) {
        state.token = '';
        state.user = null;
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);
        // 会话 id 是服务端按用户隔离的，换账号必须清掉 ——
        // 留着会让新用户的第一句话被接到上一个用户的对话里
        state.aiConversationId = '';
        state.aiEnabled = false;
        $('ai-thread').innerHTML = '';
        $('btn-ai-reset').classList.add('hidden');
        $('app-view').classList.add('hidden');
        $('login-view').classList.remove('hidden');
        if (!silent) toast('已退出登录');
    }

    function enterApp() {
        $('login-view').classList.add('hidden');
        $('app-view').classList.remove('hidden');
        renderUser();
        loadAll();
        initAi();
    }

    /* ------------------------------------------------------------------ 渲染 --- */

    function renderUser() {
        var u = state.user || {};
        var name = u.nickname || u.username || '—';
        $('user-name').textContent = name;
        $('user-avatar').textContent = (u.username || '?').charAt(0).toUpperCase();
    }

    function renderOverview(o) {
        $('c-today-rate').textContent = pct(o.todayCompletionRate);
        $('c-today-bar').style.width = Math.min(100, Math.round((o.todayCompletionRate || 0) * 100)) + '%';
        $('c-today-foot').textContent =
            '共 ' + o.todayTotalTasks + ' 项 · 完成 ' + o.todayDoneTasks +
            ' · 待办 ' + o.todayPendingTasks +
            (o.todaySkippedTasks ? ' · 跳过 ' + o.todaySkippedTasks : '') +
            ' · 实际投入 ' + o.todayActualMinutes + ' 分钟';

        $('c-streak').textContent = o.currentStreakDays;
        $('c-streak-foot').textContent =
            '历史最长 ' + o.longestStreakDays + ' 天 · 累计打卡 ' + o.totalCheckinDays + ' 天' +
            (o.lastCheckinDate ? ' · 最近 ' + o.lastCheckinDate : '');

        $('c-total-hours').textContent = minutesToHours(o.totalActualMinutes);
        $('c-total-foot').textContent =
            '累计任务 ' + o.totalTasks + ' 项 · 完成 ' + o.totalDoneTasks + ' 项';

        if (o.daysUntilExam === null || o.daysUntilExam === undefined) {
            $('c-exam-days').textContent = '—';
            $('c-exam-foot').textContent = '未设置考试日期';
        } else if (o.daysUntilExam < 0) {
            $('c-exam-days').textContent = '已结束';
            $('c-exam-foot').textContent = '考试日期 ' + o.examDate + '（已过去 ' + (-o.daysUntilExam) + ' 天）';
        } else {
            $('c-exam-days').textContent = o.daysUntilExam;
            $('c-exam-foot').textContent = '考试日期 ' + o.examDate;
        }
    }

    function subjectOf(id) {
        for (var i = 0; i < state.subjects.length; i++) {
            if (state.subjects[i].id === id) return state.subjects[i];
        }
        return null;
    }

    var PRIORITY_LABEL = { HIGH: '高', MEDIUM: '中', LOW: '低' };
    var STATUS_LABEL = { TODO: '待办', DONE: '已完成', SKIPPED: '已跳过' };

    function renderTasks(page) {
        var list = $('task-list');
        var items = page.items || [];

        if (!items.length) {
            list.innerHTML = '<div class="empty">这个筛选条件下没有任务</div>';
            return;
        }

        var html = items.map(function (t) {
            var subj = subjectOf(t.subjectId);
            var color = (subj && subj.color) || '#9A9894';
            var isDone = t.status === 'DONE';

            var actions = '';
            if (t.status === 'TODO') {
                actions = '<button class="btn btn-sm" data-act="done" data-id="' + t.id + '">完成</button>' +
                          '<button class="btn btn-sm" data-act="skip" data-id="' + t.id + '">跳过</button>';
            } else {
                actions = '<button class="btn btn-sm" data-act="todo" data-id="' + t.id + '">撤销</button>';
            }
            actions += '<button class="btn btn-sm" data-act="del" data-id="' + t.id + '">删除</button>';

            return '' +
                '<div class="task' + (isDone ? ' done' : '') + '">' +
                  '<button class="check" data-act="' + (isDone ? 'todo' : 'done') + '" data-id="' + t.id + '"' +
                          ' title="' + (isDone ? '标记为待办' : '标记为完成') + '">✓</button>' +
                  '<div class="body">' +
                    '<div class="task-title">' + esc(t.title) + '</div>' +
                    '<div class="task-meta">' +
                      '<span><i class="subject-dot" style="background:' + esc(color) + '"></i>' + esc(t.subjectName || '') + '</span>' +
                      '<span>' + esc(t.planDate) + '</span>' +
                      '<span>' + t.planMinutes + ' 分钟</span>' +
                      '<span class="pill ' + t.priority.toLowerCase() + '">' + (PRIORITY_LABEL[t.priority] || t.priority) + '</span>' +
                      '<span class="pill ' + t.status.toLowerCase() + '">' + (STATUS_LABEL[t.status] || t.status) + '</span>' +
                      (t.note ? '<span title="' + esc(t.note) + '">备注</span>' : '') +
                    '</div>' +
                  '</div>' +
                  '<div class="actions">' + actions + '</div>' +
                '</div>';
        }).join('');

        list.innerHTML = html;
    }

    /**
     * 手写柱状图。data = [{date:'2026-09-15', minutes: 120}, ...]
     *
     * 为什么不用现成的图表库：见文件头第 2 条 —— 断网要能打开。
     * 计算过程就是「找出最大值 → 按比例算高度 → 拼 SVG 字符串」。
     */
    function renderTrend(data) {
        var wrap = $('trend-chart');

        if (!data.length) {
            wrap.innerHTML = '<div class="empty">这段时间还没有打卡记录</div>';
            $('trend-total').textContent = '';
            return;
        }

        var W = 900, H = 220, padL = 34, padR = 10, padT = 14, padB = 34;
        var plotW = W - padL - padR;
        var plotH = H - padT - padB;

        var max = 0;
        var total = 0;
        data.forEach(function (d) {
            if (d.minutes > max) max = d.minutes;
            total += d.minutes;
        });
        if (max <= 0) max = 60;
        // 纵轴向上取整到 60 分钟的整数倍，刻度才好看
        max = Math.ceil(max / 60) * 60;

        var step = plotW / data.length;
        var barW = Math.max(6, step * 0.52);

        var parts = [];

        // 横向网格线 + Y 轴刻度
        for (var i = 0; i <= 4; i++) {
            var y = padT + plotH - (plotH * i / 4);
            var val = Math.round(max * i / 4);
            parts.push('<line x1="' + padL + '" y1="' + y + '" x2="' + (W - padR) + '" y2="' + y +
                       '" stroke="#EDECE8" stroke-width="1"/>');
            parts.push('<text x="' + (padL - 7) + '" y="' + (y + 4) + '" text-anchor="end" ' +
                       'font-size="11" fill="#9A9894">' + val + '</text>');
        }

        data.forEach(function (d, idx) {
            var cx = padL + step * idx + step / 2;
            var has = d.minutes > 0;
            var h = has ? Math.max(3, plotH * d.minutes / max) : 0;
            var x = cx - barW / 2;
            var y = padT + plotH - h;

            if (has) {
                parts.push('<rect x="' + x + '" y="' + y + '" width="' + barW + '" height="' + h +
                           '" rx="3" fill="#4F46E5"/>');
                parts.push('<text x="' + cx + '" y="' + (y - 5) + '" text-anchor="middle" ' +
                           'font-size="11" fill="#6B6A67">' + d.minutes + '</text>');
            } else {
                // 没打卡的那天画一个小灰点，避免「柱子没了」看起来像渲染失败
                parts.push('<circle cx="' + cx + '" cy="' + (padT + plotH - 2) + '" r="2.5" fill="#D6D5D0"/>');
            }

            // X 轴日期：只显示「月-日」，且每 2 根显示一个，避免挤在一起
            if (idx % 2 === 0) {
                var mmdd = d.date.slice(5);
                parts.push('<text x="' + cx + '" y="' + (H - 12) + '" text-anchor="middle" ' +
                           'font-size="11" fill="#9A9894">' + mmdd + '</text>');
            }
        });

        // 有打卡的日子在底部补一条浅色底纹，让「连续」看得出来
        data.forEach(function (d, idx) {
            if (d.minutes <= 0) return;
            var cx = padL + step * idx + step / 2;
            parts.push('<rect x="' + (cx - barW / 2) + '" y="' + (padT + plotH + 3) + '" width="' + barW +
                       '" height="3" rx="1.5" fill="#C9E7DA"/>');
        });

        wrap.innerHTML = '<svg viewBox="0 0 ' + W + ' ' + H + '" role="img" ' +
                         'aria-label="近 14 天每日投入分钟数柱状图">' + parts.join('') + '</svg>';

        $('trend-total').textContent = '合计 ' + total + ' 分钟（' + minutesToHours(total) + ' 小时）';
    }

    function renderBoard(board) {
        var el = $('board');
        var subjects = board.subjects || [];

        $('board-range').textContent =
            '最近 ' + board.periodDays + ' 天（' + board.from + ' ~ ' + board.to + '）· 整体达成率 ' +
            pct(board.overallAchievementRate);

        if (!subjects.length) {
            el.innerHTML = '<div class="empty">还没有科目</div>';
            return;
        }

        el.innerHTML = subjects.map(function (s) {
            var rate = s.achievementRate || 0;
            // 达成率可能超过 1（超额完成），进度条封顶 100%，但数字照实显示
            var barW = Math.min(100, Math.round(rate * 100));
            var color = s.color || '#4F46E5';
            return '' +
                '<div class="board-row">' +
                  '<div class="name"><i class="subject-dot" style="background:' + esc(color) + '"></i>' + esc(s.name) + '</div>' +
                  '<div class="bars">' +
                    '<div class="top">' +
                      '<span>实际 <b>' + s.periodMinutes + '</b> / 目标 <b>' + s.targetMinutesInPeriod + '</b> 分钟' +
                        ' · 周目标 ' + s.targetMinutesPerWeek + ' 分钟</span>' +
                      '<span>达成 <b>' + pct(rate) + '</b></span>' +
                    '</div>' +
                    '<div class="progress"><i style="width:' + barW + '%;background:' + esc(color) + '"></i></div>' +
                    '<div class="top" style="margin-top:5px">' +
                      '<span>任务 ' + s.taskTotal + ' 项 · 完成 ' + s.taskDone + ' 项 · 完成率 ' + pct(s.completionRate) + '</span>' +
                      '<span>' + (s.lastCheckinDate ? '最近打卡 ' + s.lastCheckinDate : '还没有打卡') + '</span>' +
                    '</div>' +
                  '</div>' +
                '</div>';
        }).join('');
    }

    /* -------------------------------------------------------------- AI 助手 --- */

    /**
     * 把模型返回的文本渲染成安全的 HTML。
     *
     * **先转义、再套格式**，顺序不能反：模型输出是不可信内容
     * （它可能把用户输入的内容原样带回来，而用户输入里可以有 `<script>`），
     * 先转义能保证任何标签都变成字面文本。之后替换的 `**加粗**` 是我们自己生成的
     * 白名单标签，不含用户可控内容，所以是安全的。
     *
     * 换行靠 CSS 的 `white-space: pre-wrap` 保留，不在这里转 `<br>`。
     */
    function formatAnswer(text) {
        return esc(text)
            .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
            .replace(/^\s*[-*]\s+/gm, '· ');
    }

    /** 问后端 AI 是否可用。没配 key 时后端照常启动，只是这个接口会说 enabled=false。 */
    function initAi() {
        return api('/ai/status')
            .then(function (s) {
                state.aiEnabled = !!(s && s.enabled);
                $('ai-off').classList.toggle('hidden', state.aiEnabled);
                $('ai-on').classList.toggle('hidden', !state.aiEnabled);
                $('ai-model').textContent = state.aiEnabled ? (s.model || '') : '';
                $('ai-note').textContent = state.aiEnabled ? '只读查询 · 可多轮追问' : '';
            })
            .catch(function () {
                // 查不到状态就按「不可用」处理，并如实说明，而不是留一个点了会报错的输入框
                state.aiEnabled = false;
                $('ai-on').classList.add('hidden');
                $('ai-off').classList.remove('hidden');
            });
    }

    function appendAiMessage(role, innerHtml) {
        var wrap = document.createElement('div');
        wrap.className = 'ai-msg ' + role;
        wrap.innerHTML = '<span class="who">' + (role === 'user' ? '你' : '助手') + '</span>' +
                         '<div class="ai-bubble">' + innerHtml + '</div>';
        var thread = $('ai-thread');
        thread.appendChild(wrap);
        wrap.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        return wrap;
    }

    function setAiBusy(busy) {
        $('ai-input').disabled = busy;
        $('ai-submit').disabled = busy;
        Array.prototype.forEach.call($('ai-samples').querySelectorAll('.chip'), function (b) {
            b.disabled = busy;
        });
    }

    function askAi(question) {
        if (!state.aiEnabled) return;
        question = (question || '').trim();
        if (!question) return;

        appendAiMessage('user', esc(question));

        var input = $('ai-input');
        input.value = '';
        setAiBusy(true);

        // 先放一个「思考中」的占位，拿到结果后就地替换 —— 比清空整个列表再重画体验好，
        // 也不会让用户在等待期间以为按钮没反应
        var pending = appendAiMessage('assistant', '<span class="ai-dots">思考中</span>');

        api('/ai/ask', {
            method: 'POST',
            body: {
                question: question,
                // 留空时不传这个字段（JSON.stringify 会丢掉 undefined），服务端据此开新对话
                conversationId: state.aiConversationId || undefined
            }
        })
            .then(function (data) {
                state.aiConversationId = data.conversationId;
                $('btn-ai-reset').classList.remove('hidden');
                renderAiAnswer(pending, data);
            })
            .catch(function (err) {
                // 后端在模型不可用时返回 503 + 明确原因，这里原样展示。
                // **不能吞掉**：用户会把它误读成「我确实没有数据」。
                pending.innerHTML = '<span class="who">助手</span>' +
                                    '<div class="ai-err">' + esc(err.message) + '</div>';
            })
            .finally(function () {
                setAiBusy(false);
                input.focus();
            });
    }

    function renderAiAnswer(node, data) {
        var used = data.toolsUsed || [];
        var trace = used.length
            ? '查了 ' + used.map(function (t) { return '<span class="ai-tool">' + esc(t) + '</span>'; }).join('')
            : '<span style="color:var(--warning)">这次没有查询数据</span>';

        // degraded：有工具调用失败，模型是在数据不全的前提下作答的 —— 必须告诉用户，
        // 否则他会以为这就是全部数据
        var warn = data.degraded
            ? '<div class="ai-warn">本次有工具调用失败，回答依据的数据可能不完整。</div>'
            : '';

        node.innerHTML = '<span class="who">助手</span>' +
                         '<div class="ai-bubble">' + formatAnswer(data.answer) + '</div>' +
                         warn +
                         '<div class="ai-tools">' + trace + ' · ' + data.elapsedMs + ' ms</div>';
    }

    function resetAiConversation() {
        state.aiConversationId = '';
        $('ai-thread').innerHTML = '';
        $('btn-ai-reset').classList.add('hidden');
        toast('已开始新对话');
    }

    /* --------------------------------------------------------------- 数据加载 --- */

    function loadSubjects() {
        return api('/subjects').then(function (list) {
            state.subjects = list || [];

            // 任务列表的科目筛选下拉
            var sel = $('filter-subject');
            sel.innerHTML = '<option value="">全部科目</option>' + state.subjects.map(function (s) {
                return '<option value="' + s.id + '">' + esc(s.name) + '</option>';
            }).join('');
            sel.value = state.subjectFilter;

            // 新建任务的科目下拉
            var fsel = $('f-subject');
            fsel.innerHTML = state.subjects.map(function (s) {
                return '<option value="' + s.id + '">' + esc(s.name) + '</option>';
            }).join('');
        });
    }

    function loadTasks() {
        var q = ['page=1', 'size=50', 'sortBy=planDate', 'direction=desc'];
        if (state.statusFilter) q.push('status=' + encodeURIComponent(state.statusFilter));
        if (state.subjectFilter) q.push('subjectId=' + encodeURIComponent(state.subjectFilter));

        return api('/tasks?' + q.join('&')).then(renderTasks);
    }

    function loadTrend() {
        var to = todayStr();
        var from = shiftDays(to, -13);
        return api('/checkins/daily?from=' + from + '&to=' + to).then(function (rows) {
            // 后端只返回「有打卡的日期」，缺的日期要在这里补 0 ——
            // 否则柱状图会把不连续的日子画成连续的，看起来像天天都在学
            var map = {};
            (rows || []).forEach(function (r) { map[r.date] = r.minutes; });

            var series = [];
            for (var i = 0; i < 14; i++) {
                var d = shiftDays(from, i);
                series.push({ date: d, minutes: map[d] || 0 });
            }
            renderTrend(series);
        });
    }

    function loadAll() {
        loadSubjects()
            .then(function () {
                return Promise.all([
                    api('/stats/overview').then(renderOverview),
                    loadTasks(),
                    loadTrend(),
                    api('/stats/subjects?days=7').then(renderBoard)
                ]);
            })
            .catch(function (err) {
                toast(err.message, true);
            });
    }

    /* ------------------------------------------------------------------ 交互 --- */

    function changeStatus(id, status) {
        api('/tasks/' + id + '/status', { method: 'PATCH', body: { status: status } })
            .then(function () {
                return Promise.all([
                    api('/stats/overview').then(renderOverview),
                    loadTasks(),
                    loadTrend(),
                    api('/stats/subjects?days=7').then(renderBoard)
                ]);
            })
            .catch(function (err) { toast(err.message, true); });
    }

    function deleteTask(id) {
        if (!window.confirm('删除这个任务？\n\n（该任务下的打卡记录会保留 —— 「那天学了多久」是既成事实）')) return;
        api('/tasks/' + id, { method: 'DELETE' })
            .then(function () {
                toast('已删除');
                return Promise.all([
                    api('/stats/overview').then(renderOverview),
                    loadTasks(),
                    api('/stats/subjects?days=7').then(renderBoard)
                ]);
            })
            .catch(function (err) { toast(err.message, true); });
    }

    function handleCreate(ev) {
        ev.preventDefault();
        var payload = {
            subjectId: Number($('f-subject').value),
            title: $('f-title').value.trim(),
            planDate: $('f-date').value,
            planMinutes: Number($('f-minutes').value),
            priority: $('f-priority').value
        };
        var note = $('f-note').value.trim();
        if (note) payload.note = note;

        api('/tasks', { method: 'POST', body: payload })
            .then(function () {
                toast('已创建');
                $('create-form-wrap').classList.add('hidden');
                $('create-form').reset();
                $('f-date').value = todayStr();
                $('f-minutes').value = 60;
                return Promise.all([
                    api('/stats/overview').then(renderOverview),
                    loadTasks(),
                    api('/stats/subjects?days=7').then(renderBoard)
                ]);
            })
            .catch(function (err) { toast(err.message, true); });
    }

    function bindEvents() {
        $('auth-form').addEventListener('submit', handleAuthSubmit);
        $('auth-switch').addEventListener('click', function () {
            setAuthMode(state.authMode === 'login' ? 'register' : 'login');
        });
        $('btn-logout').addEventListener('click', function () { doLogout(false); });

        // 状态筛选
        $('status-tabs').addEventListener('click', function (ev) {
            var btn = ev.target.closest('.tab');
            if (!btn) return;
            Array.prototype.forEach.call(this.querySelectorAll('.tab'), function (b) {
                b.classList.toggle('active', b === btn);
            });
            state.statusFilter = btn.getAttribute('data-status');
            loadTasks().catch(function (err) { toast(err.message, true); });
        });

        // 科目筛选
        $('filter-subject').addEventListener('change', function () {
            state.subjectFilter = this.value;
            loadTasks().catch(function (err) { toast(err.message, true); });
        });

        // 新建任务表单开关
        $('btn-toggle-form').addEventListener('click', function () {
            var wrap = $('create-form-wrap');
            wrap.classList.toggle('hidden');
            if (!wrap.classList.contains('hidden')) {
                if (!$('f-date').value) $('f-date').value = todayStr();
                $('f-title').focus();
            }
        });
        $('create-form').addEventListener('submit', handleCreate);

        // 任务行上的按钮（事件委托 —— 任务列表是动态重渲染的，
        // 逐个绑监听会在每次刷新后失效，这是最容易踩的坑之一）
        $('task-list').addEventListener('click', function (ev) {
            var btn = ev.target.closest('[data-act]');
            if (!btn) return;
            var id = Number(btn.getAttribute('data-id'));
            var act = btn.getAttribute('data-act');
            if (act === 'done') changeStatus(id, 'DONE');
            else if (act === 'skip') changeStatus(id, 'SKIPPED');
            else if (act === 'todo') changeStatus(id, 'TODO');
            else if (act === 'del') deleteTask(id);
        });

        // AI 助手
        $('ai-form').addEventListener('submit', function (ev) {
            ev.preventDefault();
            askAi($('ai-input').value);
        });
        // 示例问题也是事件委托：它们是静态的，但和任务列表保持同一种写法更好维护
        $('ai-samples').addEventListener('click', function (ev) {
            var chip = ev.target.closest('.chip');
            if (chip) askAi(chip.getAttribute('data-q'));
        });
        $('btn-ai-reset').addEventListener('click', resetAiConversation);
    }

    /* ------------------------------------------------------------------ 启动 --- */

    function boot() {
        bindEvents();
        setAuthMode('login');
        $('f-date').value = todayStr();
        $('doc-link').title = '点击打开接口文档';

        // 本地存过 token 就直接进主界面；token 失效会在第一次请求时被 401 打回登录页
        var cached = localStorage.getItem(USER_KEY);
        if (state.token && cached) {
            try { state.user = JSON.parse(cached); } catch (e) { state.user = null; }
            if (state.user) {
                enterApp();
                return;
            }
        }
        $('login-view').classList.remove('hidden');
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', boot);
    } else {
        boot();
    }
})();
