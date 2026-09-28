# exam-tracker · 备考任务追踪

> 面向备考者的**学习管理系统**：科目 → 每日任务 → 学习打卡 → 进度统计，一条闭环。
> 用 Spring Boot 3.5 / Java 17 从零手写后端，**外加一个零依赖零构建的静态前端** ——
> `./run.cmd` 一条命令起服务，浏览器打开就是能点的界面，不用配任何东西。

<p>
<img alt="Java" src="https://img.shields.io/badge/Java-17-007396">
<img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.5.14-6DB33F">
<img alt="MySQL" src="https://img.shields.io/badge/MySQL-8.0-4479A1">
<img alt="frontend" src="https://img.shields.io/badge/frontend-0%20deps%20%2F%203%20files-4F46E5">
<img alt="CI" src="https://github.com/wpc725562-dotcom/exam-tracker/actions/workflows/ci.yml/badge.svg">
<img alt="tests" src="https://img.shields.io/badge/unit%20tests-217%20passing-brightgreen">
<img alt="integration" src="https://img.shields.io/badge/integration%20tests-29%20passing-brightgreen">
<img alt="e2e" src="https://img.shields.io/badge/e2e-86%2F86%20passing-brightgreen">
<img alt="web" src="https://img.shields.io/badge/web%20contract-82%2F82%20passing-brightgreen">
<img alt="render" src="https://img.shields.io/badge/render%20check-23%2F23%20passing-brightgreen">
</p>

**演示账号：`demo` / `demo123456`** —— 登录后看到的是 `sql/seed.sql` 灌入的 4 个科目、
37 条任务、23 天打卡记录（相对今天计算，所以演示数据不会过期）。

<p align="center">
  <img src="docs/screenshots/03-dashboard-top.png" alt="仪表盘" width="820">
</p>

---

## 日本語

exam-tracker は、資格試験の受験者向けの**学習管理システム**です。科目・日次タスク・
学習チェックイン・進捗統計を提供します。バックエンドは Spring Boot 3.5 / Java 17 で
一から実装し、**依存ゼロ・ビルド不要の静的フロントエンド**を同梱しています。

- **技術スタック**：Java 17 / Spring Boot 3.5 / Spring Security + JWT / Spring Data JPA / MySQL 8 / Springdoc OpenAPI
- **設計方針**：すべてのクエリに `user_id` 条件を含めることで、ID を推測されても他人のデータに到達できないようにしています（IDOR 対策）
- **品質**：単体テスト **217 件**、統合テスト **29 件**（実 MySQL + 実 HTTP、`mvn verify` に組み込み済み）、E2E 検証 **86 項目**、Web 契約検証 **82 項目**、描画セルフチェック **23 項目**をすべてパス（実測ログを本 README に掲載）
- **同梱物**：ブラウザ UI、Swagger UI（`/api/doc.html`）、建表 SQL（`sql/schema.sql`）、デモデータ（`sql/seed.sql`）、Dockerfile、docker-compose.yml

## English

exam-tracker is a **study-management system** for exam candidates: subjects, daily
tasks, study check-ins and progress statistics. The backend is written from scratch in
Spring Boot 3.5 / Java 17, and ships with a **zero-dependency, zero-build static frontend**.

- **Stack**: Java 17, Spring Boot 3.5, Spring Security + JWT, Spring Data JPA, MySQL 8, Springdoc OpenAPI
- **Design**: every query is scoped by `user_id`, so guessing an ID never reaches another user's data (IDOR defence)
- **Quality**: **217 unit tests**, **29 integration tests** (real MySQL + real HTTP, wired into `mvn verify`), **86 end-to-end assertions**, **82 web-contract assertions** and **23 render self-checks**, all passing (measured output included below)
- **Ships with**: a browser UI, Swagger UI at `/api/doc.html`, DDL in `sql/schema.sql`, demo data in `sql/seed.sql`, Dockerfile, docker-compose.yml

---

## 1. 它解决什么问题

备考的人真正需要的不是「待办清单」，而是回答三个问题：

| 问题 | 本项目的回答 |
|---|---|
| 今天该做什么？ | 每日任务（科目 / 计划日期 / 计划时长 / 优先级） |
| 我到底学了多久？ | 学习打卡（**实际**投入分钟数，可与任务无关） |
| 我是在进步还是在自我感动？ | 四科看板（窗口内达成率）、连续打卡天数、考试倒计时 |

关键点是**「计划」和「实际」是两个字段**。只记「任务完成没」会丢掉「计划 90 分钟、实际只学了 20 分钟」
这类信息 —— 而备考里这恰恰是最需要看见的。

---

## 2. 界面

界面是**三个静态文件**（`index.html` + `app.js` + `style.css`，共 1,299 行），
由 Spring Boot 直接从 jar 里托管 —— **没有 Node、没有 npm、没有构建步骤、没有 CDN**。

这不是为了炫技，是为了**能跑**：演示时经常没有稳定网络，用 CDN 图表库的话断网就是一片空白。
所以连趋势图都是手写 SVG 画的（`renderTrend()`，约 60 行），不引任何图表库。

### 2.1 登录

<p align="center">
  <img src="docs/screenshots/01-login.png" alt="登录页" width="600">
</p>

演示账号直接印在页面上（`demo` / `demo123456`），不用去翻文档找。

### 2.2 仪表盘

<p align="center">
  <img src="docs/screenshots/03-dashboard-top.png" alt="仪表盘" width="860">
</p>

四个卡片回答四个问题：**今天完成了多少 / 连续打卡几天 / 一共投入多久 / 距考试还有几天**。

第二张卡片的副标题里藏着对比信息 ——「历史最长 10 天 · 累计打卡 23 天」。
当前 7 天、历史最长 10 天，差的那 3 天说明中间断过签。**这个对比是刻意留出来的**：
种子数据里 D-7 那天只有「跳过」没有打卡，否则「历史最长」这个指标永远等于当前值，没有意义。

### 2.3 任务列表

<p align="center">
  <img src="docs/screenshots/08-task-list.png" alt="任务列表" width="860">
</p>

每行是：复选框 · 标题 · **科目色点** · 计划日期 · 计划分钟 · 优先级 · 状态 · 备注。
状态 tab 和科目下拉可以组合筛选（服务端筛选，不是前端过滤）。

### 2.4 近 14 天投入趋势

<p align="center">
  <img src="docs/screenshots/06-trend-chart.png" alt="趋势图" width="860">
</p>

手写 SVG。两个细节：

- **没打卡的那天画一个灰色小点**（图里的 09-21），而不是什么都不画 ——
  否则柱子之间空一格，看起来像渲染失败而不是「那天没学」。
- 后端只返回**有打卡的日期**，前端要补 0 成完整 14 天，不然不连续的日子会被画成连续的。

### 2.5 四科看板

<p align="center">
  <img src="docs/screenshots/07-board.png" alt="四科看板" width="860">
</p>

每个科目一行：窗口内**实际 / 目标**分钟、达成率、任务完成率、最近打卡日。
颜色和任务列表里的科目色点是同一套（存在 `subject.color` 里），所以两处天然一致。

看板回答的是第 1 节那个问题 ——「**我是在进步还是在自我感动？**」
比如图里英语 133 / 300 分钟（44%）而计算机 413 / 480（86%），偏科一眼可见。

---

## 3. 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 语言 / 运行时 | Java 17（LTS） | 用 `record`、文本块、`switch` 表达式 |
| 框架 | Spring Boot **3.5.14** | Web / Validation / Actuator |
| 持久化 | Spring Data JPA + Hibernate | 复杂筛选走 `Specification` |
| 数据库 | MySQL **8.0** | 表结构由 `sql/schema.sql` 管理，应用侧 `ddl-auto: validate` |
| 安全 | Spring Security + **jjwt 0.12.6** | 无状态 JWT，BCrypt 存密码 |
| 文档 | Springdoc OpenAPI **2.8.17** | Swagger UI：`/api/doc.html` |
| 前端 | **原生 HTML + CSS + ES5 JavaScript** | 3 个文件 / 1,299 行，零依赖零构建 |
| 测试（单元） | JUnit 5 + Mockito + AssertJ + MockMvc | `mvn test` · **surefire** · 217 个用例，纯 Mockito 不启动容器 |
| 测试（集成） | JUnit 5 + 真实 MySQL + 真实 HTTP | `mvn verify` · **failsafe** · 29 个用例，启动完整 Spring 容器 |
| 构建 | Maven（`./mvnw`，无需预装） | 打包出可执行 fat jar |
| CI | GitHub Actions | `mvn verify` + MySQL 8 service，每次 push / PR 自动跑 |

规模：**50 个主源文件 / 3,862 行**，**17 个测试文件 / 4,588 行**（测试比主代码还多 19%），
**3 个前端文件 / 1,299 行**。

---

## 4. 快速开始

### 4.1 一条命令（推荐）

Windows —— 双击 `run.cmd`，或在终端里：

```cmd
run.cmd
```

Linux / macOS：

```bash
./run.sh
```

两个脚本逻辑一致，会依次：检查 Java → 找（或构建）jar → 探测 MySQL 端口 →
按需建库并灌入演示数据 → 启动。每一步都打印在做什么，**失败会明确告诉你卡在哪一步**，
不会静默跳过。

| 参数 | 作用 |
|---|---|
| （无） | 有 jar 就直接用；没有才构建。数据库不可达时会问你要不要建 |
| `init` | 建库 + 灌 `sql/seed.sql` 演示数据，然后退出（不启动） |
| `skip` | 跳过所有数据库检查，直接用现有 jar 启动（改代码时最快） |

启动后终端会打印：

```
Starting exam-tracker ...

  Web UI  : http://127.0.0.1:8090/api/
  API doc : http://127.0.0.1:8090/api/doc.html
  Login   : demo / demo123456

  Press Ctrl+C to stop.
```

**打开 <http://127.0.0.1:8090/api/> 就是第 2 节那个界面**，用 `demo` / `demo123456` 登录。

> `run.cmd` 是**纯 ASCII** 的，这不是洁癖：`cmd.exe` 用 OEM 代码页（中文 Windows 是 GBK）
> 读 `.cmd` 文件，UTF-8 中文注释会被错误解码，乱码字节可能从 `REM` 行里漏出来被当成命令执行 ——
> 结果是**启动其实成功了，但屏幕上蹦出「不是内部或外部命令」**，让人去找一个根本不存在的 bug。
> 所以里面只有英文注释，文件末尾也是 CRLF 而不是 LF。

### 4.2 手动三步

```bash
# 1) 建库建表（幂等，可重复执行）
mysql -h 127.0.0.1 -P 3308 -uroot -p < sql/schema.sql

# 1b) 灌演示数据（可选，但强烈建议 —— 空库打开界面什么都看不到）
mysql -h 127.0.0.1 -P 3308 -uroot -p exam_tracker < sql/seed.sql

# 2) 打包（会先跑 217 个单元测试）
./mvnw package

# 2b) 想连集成测试一起跑（需要一个真实可连的 MySQL，见 8.2）
./mvnw verify

# 3) 启动
java -jar target/exam-tracker-1.0.0.jar
```

启动日志（实测）：

```
Tomcat started on port 8090 (http) with context path '/api'
Started ExamTrackerApplication in 6.391 seconds (process running for 6.848)
```

验证：

```bash
curl --noproxy '*' http://127.0.0.1:8090/api/health
# {"status":"ok"}
```

> `--noproxy '*'` 不是多余的：本机开发环境有系统代理，它会把 `127.0.0.1` 的请求也劫持走，
> 表现为 curl 超时 —— 看起来像服务没起来，其实起来了。

### 4.3 演示数据（`sql/seed.sql`）

`demo` 账号看到的所有内容都来自这个脚本。它有两个设计要点：

**① 用 `CURDATE()` 相对计算，不写死日期。** 写死「2026-09-28」的话，
过几天再演示，界面上全是「过期任务」，「今日完成率」也永远是 0。所以种子数据全部相对今天生成。

**② 幂等，可以反复灌。** 靠的是 `app_user` 上的 `ON DELETE CASCADE`：
先删 `demo` 用户，级联带走他的科目 / 任务 / 打卡，再重新插入。

这里踩过一个坑：最初用 `MOD(t.id, 5)` 当「实际时长」的扰动系数，结果**重灌后数字会漂**
（1916 → 1912 → 1921）—— 因为 `id` 是自增的，重灌之后整体后移了。改成
`MOD(DAYOFYEAR(plan_date) + plan_minutes, 5)` 之后，连跑三次都是同一个数字。

灌完后的自检输出（连跑三次完全一致）：

| 指标 | 值 |
|---|---:|
| 科目数 | 4 |
| 任务总数 | 37 |
| 已完成 | 27 |
| 打卡记录 | 37 |
| 打卡天数 | 23 |
| 累计投入分钟 | 1905 |
| 当前连续打卡 | 7 天 |
| 历史最长连续 | 10 天 |

**当前 7 天 / 历史最长 10 天不是巧合**：种子数据里 D-7 那天只标了「跳过」没有打卡，
刻意留出一个断签，否则「历史最长」永远等于当前值，这个指标就没有对比意义了。
更早的打卡里也留了 D-15 / D-14 两天空档。

### 4.4 全部可覆盖的环境变量

| 变量 | 默认值 | 用途 |
|---|---|---|
| `SERVER_PORT` | `8090` | 端口 |
| `SERVER_ADDRESS` | `127.0.0.1` | 监听地址。**默认只绑回环**；Docker 里必须设成 `0.0.0.0` |
| `DB_HOST` / `DB_PORT` | `127.0.0.1` / `3308` | 数据库地址 |
| `DB_NAME` | `exam_tracker` | 库名 |
| `DB_USERNAME` / `DB_PASSWORD` | `dev` / `dev123456` | 数据库账号 |
| `JWT_SECRET` | 开发用默认值（64 字节） | **生产必须覆盖**：`openssl rand -base64 48` |
| `JWT_EXPIRE_MINUTES` | `720` | token 有效期（分钟） |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` | 逗号分隔 |
| `LOG_LEVEL` | `INFO` | 应用日志级别 |
| `DB_POOL_SIZE` | `10` | Hikari 连接池上限 |

> ⚠️ 有一个坑：**环境变量优先级低于命令行参数，但高于 `application.yml` 里的默认值**。
> 如果 shell 里已经存在一个 `SERVER_PORT`（或 Spring 的 `SERVER__PORT`），它会覆盖 yml，
> 应用就跑到别的端口上去了。排查时用 `--server.port=8090` 显式指定，命令行参数优先级最高。

### 4.5 Docker

```bash
docker compose up -d --build
curl --noproxy '*' http://127.0.0.1:8090/api/health
```

compose 会另起一个 MySQL（映射到宿主机的 **13308**，不和工作区的 3308 撞），
并自动执行 `sql/schema.sql`。

> ⚠️ **诚实声明**：`Dockerfile` 与 `docker-compose.yml` **没有在真实 Docker 上构建验证过** ——
> 开发机的 Docker 起不来（`hypervisorlaunchtype=Off`，见工作区 `docs/runtime.md`）。
> 文件内容是按官方文档写的，但「能跑」请以你自己 `docker compose up` 的结果为准。
> 本 README 里所有「实测」字样指的都是 **4.1 / 4.2 的本地 jar 路线**。

---

## 5. API 一览

统一前缀 `/api`（`server.servlet.context-path`），**14 个路径 / 22 个操作**。

所有响应都是同一个外壳（`/health` 除外，它刻意返回裸 JSON）：

```json
{ "code": 0, "message": "成功", "data": { } }
```

`code` 为业务码：`0` 成功，`4xxxx` 客户端问题，`5xxxx` 服务端问题 —— **前三位对齐 HTTP 状态码**。

### 认证 `/auth`

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| POST | `/auth/register` | 注册（用户名全局唯一，BCrypt 存密码） | 公开 |
| POST | `/auth/login` | 登录，返回 JWT | 公开 |
| GET | `/auth/me` | 当前用户信息 | ✅ |
| PATCH | `/auth/me` | 修改昵称 / 考试日期（全量替换语义） | ✅ |

### 科目 `/subjects`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/subjects` | 科目列表（按 `sortOrder`） |
| GET | `/subjects/{id}` | 详情 |
| POST | `/subjects` | 新建（同一用户下不可重名） |
| PUT | `/subjects/{id}` | 修改 |
| DELETE | `/subjects/{id}?force=` | 删除。**默认拒绝**有数据的科目（409），`force=true` 才级联 |

### 任务 `/tasks`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/tasks` | 分页 + 筛选（`from` / `to` / `subjectId` / `status` / `priority` / `keyword` / `sortBy` / `direction`） |
| GET | `/tasks/{id}` | 详情 |
| POST | `/tasks` | 新建 |
| PUT | `/tasks/{id}` | 修改（不含状态） |
| PATCH | `/tasks/{id}/status` | 变更状态（`TODO` / `DONE` / `SKIPPED`） |
| DELETE | `/tasks/{id}` | 删除（**不删打卡记录**） |

### 打卡 `/checkins`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/checkins` | 新增。`subjectId` 与 `taskId` 二选一，传了 `taskId` 则以任务为准 |
| GET | `/checkins` | 列表。不传日期时**默认只回溯 30 天** |
| GET | `/checkins/daily` | 按天汇总（日历视图） |
| DELETE | `/checkins/{id}` | 删除 |

### 统计 `/stats`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/stats/overview` | 仪表盘：今日任务 / 连续打卡 / 累计时长 / 考试倒计时 |
| GET | `/stats/subjects?days=` | 四科看板：窗口内达成率（`days` 上限 365，默认 7） |

### 其它

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 存活探针。**不查任何依赖**，返回裸 JSON |
| GET | `/actuator/health` | 带依赖状态的健康检查（只暴露 `health` / `info`） |

---

## 6. 架构与分层

```
浏览器 ──▶ static/ 三个文件（index.html + app.js + style.css）
   │             零依赖、零构建，手写 SVG 图表
   │ fetch('/api/...')，带 Bearer token
   ▼
HTTP ──▶ JwtAuthenticationFilter ──▶ Controller ──▶ Service ──▶ Repository ──▶ MySQL
              （验签 → UserPrincipal）     DTO         业务规则       Specification
                                            │             │
                                       GlobalExceptionHandler（统一错误外壳）
```

```
com.wpc725562.examtracker
├── domain/          实体：User / Subject / Task / Checkin + 枚举 + BaseTimeEntity
├── repository/      Spring Data 接口 + projection/（record 投影，不用 Object[]）
├── dto/             record 形式的请求/响应 + 校验注解
├── service/         业务规则（StreakCalculator 是纯函数，便于单测）
├── controller/      HTTP 边界，只做参数搬运与响应包装
├── security/        JWT 签发/校验、UserPrincipal、401/403 的 JSON 输出
├── config/          SecurityConfig、OpenApiConfig
└── common/          ApiResponse / PageResult / ErrorCode / BusinessException / SortResolver
```

**分层纪律**：Service 层不出现任何 `HttpServletRequest`、`ResponseEntity`；
错误只用 `BusinessException(ErrorCode, message)` 表达，状态码由 `GlobalExceptionHandler` 统一决定。
这样 Service 才好写单元测试（本项目 Service 层的测试全部不启动 Spring 容器）。

前端不在这个包里 —— 它是 `src/main/resources/static/` 下的三个普通文件，
被打进 jar 的 `BOOT-INF/classes/static/`。

**前端与后端同源部署**，这是一个刻意的取舍：

- **好处**：没有跨域问题，不用维护两套启动流程，演示时只有一条命令。
- **代价**：改了前端要重新打包（或者手动把文件拷进 `target/classes/static/`）。
  如果想让前端独立开发，`CORS_ALLOWED_ORIGINS` 已经预留好了（默认放行 `localhost:5173` / `3000`）。

> 顺带一个踩过的坑：静态资源必须加进 `SecurityConfig` 的 `PUBLIC_PATHS`，
> 而且写的是**去掉 context-path 之后的路径**（`/`、`/app.js`、`/style.css`，不是 `/api/app.js`）——
> 因为 `requestMatchers` 匹配的是 DispatcherServlet 拿到的那个路径。
> 忘了放行的话，**登录页本身会被 401 挡掉**，现象是「整个页面打不开」而不是「登录失败」。

---

## 7. 关键设计决定

这一节是面试里真正能展开的部分 —— 每条都是「不这么做会出什么问题」。

### 7.1 数据模型

| 决定 | 为什么 |
|---|---|
| **计划时长与实际时长分成两个字段** | 塞进一个字段就表达不了「计划 90 分钟、实际 20 分钟」，而那正是最该看见的信息 |
| **打卡记录可以挂在任务上，也可以不挂**（`checkin.task_id` 可空） | 随手翻笔记、听听力这类学习时间不属于任何任务 |
| **`checkin → task` 外键是 `ON DELETE SET NULL`，不是 `CASCADE`** | 删任务是「整理待办」，而「那天确实学了 90 分钟」是既成事实。级联删除会让用户删几个任务后发现累计时长和连续天数莫名其妙变少 |
| **`subject → task/checkin` 是 `CASCADE`** | 删科目是用户**显式确认过**的动作（要求 `force=true`），留一堆「属于已删科目」的孤儿数据没有意义 |
| **复合索引写成 `(user_id, plan_date)` 而不是 `(plan_date, user_id)`** | 本项目**每一次**查询都以 `user_id` 开头（数据隔离），列顺序反了索引就用不上 |
| **日期用 `DATE` 而不是时间戳** | 任务是按「天」规划的，带上时分秒只会引入时区歧义 |
| **枚举以字符串存**（`status` / `priority`） | 存序号的话，将来在枚举中间插一个值，历史数据全部错位 |

### 7.2 安全

| 决定 | 为什么 |
|---|---|
| **所有「按 id 查」都写成 `findByIdAndUserId(id, userId)`** | 只写 `findById(id)` 就是 IDOR：A 用户猜 id 就能读 B 的数据。把归属条件放进查询本身，比在 Service 里事后判断更不容易漏 |
| **越权返回 404 而不是 403** | 403 等于确认「这个资源存在，只是不给你看」—— 仍然泄漏了信息 |
| **登录失败时「用户名不存在」和「密码错误」提示完全相同** | 提示不一样的话，攻击者能拿一批用户名快速筛出哪些是有效账号（账号枚举） |
| **用户不存在时也跑一次 BCrypt 校验** | 否则「响应特别快」本身就泄漏了「这个用户名不存在」（时间侧信道） |
| **并发注册靠唯一索引兜底，且报错与主动查重逐字一致** | 「先查再插」在并发下会漏（check-then-act 竞态）；而两条路径报错不同的话，又会从错误信息里泄漏用户名是否存在 |
| **排序字段走白名单**（`SortResolver`） | 直接把 `sortBy` 交给 `Sort.by()` 等于让调用方探测实体结构；拼进原生 SQL 就是注入口子。且非白名单字段返回 **400 而不是 500** —— 客户端传错参数不该被记成服务端故障 |
| **`LIKE` 关键词里的 `%` / `_` / `\` 会被转义** | 不转义的话，用户搜一个 `%` 就等于搜全部，看起来像功能坏了（而且不报错） |
| **兜底异常只回固定文案** | 一旦把 `ex.getMessage()` 返回给客户端，就会漏出表名、SQL 片段、文件路径 |
| **JWT 只放 `sub` / `username`**，密钥短于 32 字节**启动即失败** | JWT 是 Base64 编码不是加密，任何人都能解开看；配置错了应该在部署那一刻就暴露 |
| **默认只绑 `127.0.0.1`** | 不写 `server.address` 时 Spring Boot 绑 `0.0.0.0`（所有网卡），配上开发用的弱口令，同网段任何设备都能连进来 |

### 7.3 正确性

| 决定 | 为什么 |
|---|---|
| **排序永远追加 `id` 作为 tie-breaker** | 排序字段有重复值时（比如同一天很多任务），没有唯一列兜底会导致翻页时同一条数据出现在两页里，或者某条一次都不出现 |
| **「完成时间有值」与「状态是 DONE」的一致性收在实体方法 `changeStatus()` 里** | 让 Service 分别 `set` 两个字段，迟早有人只改一个 |
| **分页上限 200 / 统计窗口上限 365 / 计划时长上限 1440** | 挡住 `?size=100000` 这种把库拖垮的请求；1440 = 一天 24 小时，超过一定是填错了 |
| **删科目默认 409 并告知数量，`force=true` 才级联** | 破坏性操作要**显式确认**，而不是靠「他应该知道」。用户点错一次就是历史全没 |
| **打卡不传日期时默认只回溯 30 天** | 否则「打开页面就拉全表」 |
| **`「今天还没打卡」不算断签** | 否则每天零点一过所有用户的连续天数都归零，那不是用户要的语义 |
| **列表里科目名一次查完做成 Map** | 逐条去查是典型 N+1：20 条数据变成 21 条 SQL |
| **统计聚合全部下推到数据库** | 查出一堆实体在 Java 里循环加，数据量小的时候看不出区别，上万条就会从 20ms 变成 3s |
| **完成率分母为 0 时返回 0.0** | 返回 1.0 会显示成「全部完成」，是错的；返回 NaN 会让前端显示成乱码 |
| **对外不直接序列化 Spring Data 的 `Page`** | 它的 JSON 结构随版本变过，等于把框架内部结构当成对外契约。包成自己的 `PageResult` |
| **`@Validated` 加在 Controller 类上** | 少了它，`@RequestParam` 上的 `@Min` / `@Max` 不会被 AOP 拦截，`size=99999` 会静默通过 |
| **`-parameters` 编译参数显式写进 pom** | 少了它，`@RequestParam` 不写 `name` 就会在**运行时**抛「Name for argument not specified」 |

### 7.4 可运维性

| 决定 | 为什么 |
|---|---|
| **`ddl-auto: validate`，表结构由 `sql/schema.sql` 管理** | `update` 会「顺手」改表，加字段时可能悄悄改掉列类型或丢索引；生产变更必须可审核、可回溯。`validate` 让实体与表对不上时**启动就失败** |
| **显式排除 `UserDetailsServiceAutoConfiguration`** | 否则 Spring Security 会生成一个随机密码并**打印在启动日志里**，还留下一个没人记得的登录入口 |
| **`logging.charset.console: UTF-8`** | JDK 17 在中文 Windows 上默认编码是 GBK，中文日志会变成 `δԤ���쳣` 这种乱码 —— 而且**不报错**，只是把排错时最有用的信息毁掉 |
| **`server.shutdown: graceful` + Dockerfile 用 `exec` 启动** | 让 SIGTERM 直接到 JVM，把手上正在处理的请求做完再退出 |
| **健康检查用 `/api/health` 而不是 `/actuator/health`** | 前者不查依赖。数据库短暂抖动时不应该让编排系统把所有实例同时判死 —— 那会引发雪崩 |
| **Actuator 只暴露 `health` / `info`** | `env` / `beans` 会漏出配置和内部结构 |

---

## 8. 测试与验证

### 8.1 单元测试：217 个，全部通过

```bash
./mvnw test
```

```
[INFO] Tests run: 217, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

按测试类分布：

| 用例数 | 测试类 | 覆盖重点 |
|---:|---|---|
| 25 | `TaskServiceTest` | 分页页码 0 基/1 基转换、越权 404、N+1、单页上限、状态流转 |
| 22 | `CheckinServiceTest` | 科目从任务推导、默认 30 天窗口、只为本页任务查标题、未来日期拒绝 |
| 19 | `StatsServiceTest` | 分母为 0、周目标按窗口折算、两位小数、窗口区间 |
| 19 | `StreakCalculatorTest` | 跨月 / 跨年 / 闰年 / 断签 / 「今天还没打卡」 |
| 19 | `TaskControllerTest` | **userId 来自 token 而不是请求参数**、状态码映射、异常不泄漏 |
| 17 | `AuthServiceTest` | 账号枚举防护、时间侧信道、唯一索引兜底、密码只存哈希 |
| 16 | `SubjectServiceTest` | 删除保护（409 / `force=true`）、删除顺序、颜色归一化 |
| 16 | `GlobalExceptionHandlerTest` | 12 个 handler 的状态码映射、**兜底不泄漏异常原文** |
| 16 | `ValidationTest` | 每个 DTO 的边界值（1/1440、50/51、`#RGB`/`#RRGGBB`…） |
| 10 | `JwtServiceTest` | 密钥下限、签发/校验往返、伪造签名、过期、**token 不含敏感字段** |
| 10 | `SortResolverTest` | 白名单精确匹配、400 而非 500、tie-breaker |
| 10 | `TaskSpecificationsTest` | `%` / `_` / `\` 转义、空条件返回 null |
| 7 | `TaskTest` | 状态与完成时间的不变式 |
| 5 | `PageResultTest` | 页码转换、空页 |
| 4 | `ErrorCodeTest` | 「前三位对齐 HTTP」的编码不变式 |
| 2 | `HealthControllerTest` | 探针返回裸 JSON（不套外壳） |

**测试设计上的两个取向：**

1. **Service 层测试不启动 Spring 容器**（`@ExtendWith(MockitoExtension.class)`），
   纯 Mockito。跑完 217 个用例只要 **8 秒**，而且不会因为环境问题假红。
2. **「必须永远成立」的性质单独写一条测试**，而不是只测 happy path。例如：
   - `AuthServiceTest.同一条提示` —— 断言两条失败路径的消息**逐字相等**；
   - `JwtServiceTest.tokenCarriesNoSensitiveClaims` —— 断言 claims 只有 5 个键。
     以后有人想「顺手把邮箱塞进 token 省一次查询」时，测试会立刻变红。

### 8.2 集成测试：29 个用例，`mvn verify` 一次跑完

```bash
./mvnw verify
```

```
[INFO] --- failsafe:3.5.5:integration-test (run-integration-tests) @ exam-tracker ---
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.507 s
[INFO]                -- in 端到端集成测试（真实 MySQL + 真实 HTTP）
[INFO] BUILD SUCCESS
[INFO] Total time:  15.408 s
```

**为什么要有这一层**：上面 217 个单测全是纯 Mockito（不启动 Spring 容器），跑得飞快，
但它们**验不了**这四类问题：

| 单测验不了 | 举例 |
|---|---|
| Spring Security 的**过滤器链** | 单测里 `SecurityContext` 是手工塞的，验不了「静态资源真的被放行、业务接口真的被拦住」 |
| JSON 序列化后的**字段名** | 前端按字段名取值，少一个就渲染成 `undefined`，而单测直接比对 Java 对象，根本看不到这一层 |
| JPA 生成的 **SQL 能不能跑** | 单测里 Repository 是 mock 的，SQL 语法错误、表名对不上，单测全绿 |
| **越权防护真的生效** | 单测验的是「查询条件里带了 `userId`」，验不了「带上之后真的查不到」 |

所以这一层用**真实 MySQL + 真实 HTTP**跑，10 组共 **26 个测试方法**，
其中「静态资源」那组是 `@ParameterizedTest`（4 个路径各跑一次），
所以实际执行 **29 个用例**：

| 组 | 方法数 | 内容 |
|---|---:|---|
| 1 | 2 | 静态资源匿名可达（`@ParameterizedTest`，4 个路径）+ **放行不影响安全边界**（业务接口仍 401） |
| 2 | 5 | 登录 / 注册校验 / `me` / 未认证返回 **JSON 格式的 401**（不是 HTML 重定向） |
| 3 | 2 | 科目列表字段 + 重名拒绝（约束按用户隔离） |
| 4 | 4 | 任务分页外壳、**8 个字段齐全**、状态筛选、非法枚举/分页/排序字段 → 400 |
| 5 | 2 | 总览 14 个字段 + 数值自洽 |
| 6 | 2 | 四科看板 12 个字段 + 窗口天数回显与上限 |
| 7 | 1 | 打卡按天汇总（柱状图数据源） |
| 8 | 3 | 状态流转（`completedAt` 写上/清空）、写操作错误码、未来日期拒绝 |
| 9 | 3 | **数据隔离**：B 看不到 A 的、按 id 直访 404、越权写操作后 A 的数据分毫未动 |
| 10 | 2 | 删除保护：有数据 409 → `force=true` 才级联；空科目可直接删 |

**测试数据自己造、自己清。** 不依赖 `sql/seed.sql` —— 种子数据的数字会随演示设计调整，
把断言钉在那些数字上等于让测试和演示数据互相绑架。集成测试注册两个独立用户
（`it_a_*` / `it_b_*`），自己建科目/任务/打卡，`@AfterAll` 再把自己删干净：

```
[ExamTrackerIT] 清理了 3 个测试用户（及其级联数据）
```

> 这顺带修掉了 `tools/p4-e2e-test.py` 的一个老毛病：它每次跑都会创建用户，
> **但从来不清理**，跑 20 次库里就多 20 个 `e2e_*` 用户。集成测试不会留垃圾 ——
> 实测跑完 `app_user` / `task` / `subject` / `checkin` 四张表里 `it_%` 的记录数都是 **0**。

#### 三个刻意的取舍

**① 用真实 MySQL，不用 Testcontainers。**
最初的计划是 Testcontainers（「测试自带数据库，谁跑都一样」），但**开发机的 Docker 起不来**：

```
$ docker version
Client: 29.7.2
Server:                       ← 空
failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine
```

Docker 起不来，Testcontainers 就**本地根本跑不了**这个测试 —— 那「`mvn verify` 一次跑完」
就成了空话（CI 上绿、本地红，等于没有）。改成连真实 MySQL：本地用工作区的便携版
（`127.0.0.1:3308`），CI 用 GitHub Actions 的 `services: mysql:8.0`。两边都是 MySQL 8，
SQL 语义一致，而且 CI 上还少了一层 Docker-in-Docker。
配置全部走 `application.yml` 里已有的 `${DB_HOST:...}` 这类环境变量，**测试代码里一行数据库配置都没写**。

**② `surefire` 和 `failsafe` 分开，不让集成测试混进 `test` 阶段。**
`mvn test` 只跑 `*Test`（8 秒，不依赖任何外部服务）；`mvn verify` 才额外跑 `*IT`。
理由很实际：如果 IT 混进 `test` 阶段，「跑个单测」就变成「先起数据库」——
久而久之没人跑单测了。而只有单测也验不了上面那四类问题。

> **failsafe 必须同时绑定 `integration-test` 和 `verify` 两个 goal。**
> 只绑 `integration-test` 的话，测试**失败了 `mvn verify` 依然是 BUILD SUCCESS** ——
> 一个彻头彻尾的假绿。这条不是理论，是官方文档里专门标注的坑。

**③ 断言只钉「不变式 + 下界」，不钉聚合量的具体数字。**
最初写 `userBCannotSeeUserAData` 时我断言「B 有 1 个科目」——因为它前面那个用例
给 B 建了一个同名科目。但 JUnit **不保证方法执行顺序**，于是这个断言的结果取决于
「谁先跑」：单独跑全绿，整包跑随机红。这是最隐蔽的一类 flaky。
现在改成：每个用例**用完就删自己造的数据**，聚合量只断言不变式（
`今日任务数 == 完成 + 待办 + 跳过`、`总数 >= 7`）和**本测试内记下的基线**。

**这个修复有机器证据**：用 `-Djunit.jupiter.testmethod.order.default=…MethodOrderer$Random`
把 29 个用例的执行顺序打乱重跑，**执行顺序确实变了**（比对两次的 `<testcase>` 顺序，
29 项排列完全不同），**仍然 BUILD SUCCESS**。

#### 踩过的两个坑

| 坑 | 现象 | 根因 |
|---|---|---|
| `PATCH` 请求直接抛异常 | `ProtocolException: Invalid HTTP method: PATCH` | `SimpleClientHttpRequestFactory` 基于 `HttpURLConnection`，**它不支持 PATCH**（只认 GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE）。改用 `JdkClientHttpRequestFactory`（`java.net.http.HttpClient` 原生支持 PATCH，且不用引 Apache HttpClient） |
| 断言「应当返回 401」时拿到异常而不是状态码 | `HttpClientErrorException: 401 Unauthorized` | `RestTemplate` 默认的 `DefaultResponseErrorHandler` 遇到 4xx/5xx 会**抛异常**。这个测试有大量断言是「这里应当返回 401 / 404 / 409 / 400」，所以必须换成 `hasError` 恒返回 `false` 的 no-op 处理器 |

### 8.3 端到端验证：86 项断言，全部通过

`tools/p4-e2e-test.py` 对**真实运行的实例 + 真实 MySQL** 发起 HTTP 请求，
分 9 组共 86 项断言：

| 组 | 内容 |
|---|---|
| 1 | 存活探针 / Swagger UI / OpenAPI 文档 |
| 2 | 注册（含弱密码、非法用户名）、登录、`/auth/me` |
| 3 | 未认证访问 → **JSON 格式的 401**（不是 HTML 重定向） |
| 4 | 任务 CRUD、分页、排序、**通配符转义**、非法枚举 → 400、`size` 超限 → 400 |
| 5 | 打卡：科目从任务推导、不挂任务、未来日期 → 400、按天汇总 |
| 6 | 统计：连续打卡天数、考试倒计时、四科看板、窗口折算 |
| 7 | **数据隔离**：B 用户看不到 / 改不了 / 删不了 A 的任何数据（全 404） |
| 8 | 删除保护：有数据的科目 409 → `force=true` 才级联 |
| 9 | 日志体检：`validate` 通过、无 `ERROR` 行、无未预期异常 |

实测输出（节选）：

```
[7] 数据隔离（越权防护）
  [PASS] B 看不到 A 的科目  —— B 的科目数=0
  [PASS] B 按 id 直接读 A 的科目 -> 404（不是 403，避免暴露资源是否存在）  —— HTTP 404
  [PASS] B 不能改 A 的任务 -> 404  —— HTTP 404
  [PASS] B 的统计全为 0（没有串到 A 的数据）  —— totalTasks=0 totalActualMinutes=0

[9] 日志体检  D:\java-workspace\logs\p4.log
  [PASS] 表结构校验通过（ddl-auto=validate）
  [PASS] 无真实 ERROR 行  —— 0 行

========================================================================
结果：86/86 PASS
========================================================================
```

> 说明：脚本在开发机上用 `http.client` 直连回环地址，绕过系统代理
> （系统代理会劫持 `127.0.0.1` 的请求，`curl` 也需要 `--noproxy '*'`）。

### 8.4 前端契约验证：82 项断言，全部通过

```bash
python tools/p4-web-e2e.py
```

**为什么和 8.3 分开写：两者的失败模式完全不同。** 8.3 验的是「后端行为对不对」，
这一份验的是「**前端会不会白屏**」。

举个具体的：8.3 会测「`PATCH /tasks/{id}` 改状态返回 200 且 `completedAt` 被写上」；
8.4 会测「列表接口返回的每条任务都带 `subjectName` 字段」——
因为任务行要显示科目名，后端如果只回 `subjectId`，前端就渲染成 `undefined`，
而**接口返回 200，8.3 那 86 项全绿，页面却是坏的**。

分 8 组共 82 项：

| 组 | 内容 | 前端为什么依赖它 |
|---|---|---|
| 1 | 静态资源匿名可达 | 登录页自己必须能匿名打开，否则用户根本没机会输入账号密码 |
| 2 | 认证 | 登录 / 注册 / `me` 的响应字段 |
| 3 | 科目 | 「新建任务」表单的科目下拉数据源 |
| 4 | 任务 | 列表字段齐全（含 `subjectName`）、状态筛选真的生效 |
| 5 | 仪表盘总览 | 四个卡片读的 15 个字段 |
| 6 | 四科看板 | 看板行的 12 个字段 |
| 7 | 打卡趋势 | 柱状图的数据源 |
| 8 | 写操作 | 状态流转 + 复原、404 / 400 边界 |

实测输出（节选）：

```
[7] 打卡趋势（柱状图数据源）
  [PASS] 返回按天汇总的数组
  [PASS] 每条含 date
  [PASS] 每条含 minutes
  [PASS] 日期都在请求区间内

[8] 写操作（状态流转 + 复原）
  [PASS] PATCH 标记完成返回 200
  [PASS] completedAt 被写上
  [PASS] 改回 TODO 后 completedAt 被清空
  [PASS] 改不存在的任务返回 404
  [PASS] 非法计划时长（0）返回 400

====================================================================
 结果：82 通过 / 0 失败
====================================================================
```

> 组 1 那条不是凑数的。静态资源如果不加进 Spring Security 的放行列表，
> **登录页本身会被 401 挡掉** —— 用户看到的现象是「整个页面打不开」而不是「登录失败」，
> 极易被误判成前端写坏了。这个坑真踩过。

### 8.5 渲染自检：23 项断言 + 8 张截图

```bash
NODE_PATH=<node-workspace>/node_modules node tools/screenshot.js
```

用系统 Chrome（headless）打开页面，**既截图也断言**。截图就是第 2 节用的那 8 张。

**为什么需要它**：静态前端最容易出的问题不是「报错」而是**白屏** ——
HTTP 200、静态资源也全 200，但 JS 里一个选择器写错、一个字段名对不上，整页就是一片空白，
**而所有网络层检查全是绿的**。只看 curl 的状态码完全发现不了。

所以它断言的是「渲染真的发生了」：

```
[3] 主界面渲染完整性（白屏检测）
  [PASS] 概览卡片 4 张  (4 张)
  [PASS] 卡片都有实际高度（未塌陷）  ([146,146,146,146])
  [PASS] 卡片数值都已填充（无残留 "—"）  (50% / 7天 / 31.8小时 / 167天)
  [PASS] 任务列表有内容  (37 行)
  [PASS] 趋势图 SVG 已渲染  (1 个 <svg>)
  [PASS] 趋势图有柱体  (26 个 <rect>)
  [PASS] 四科看板有内容  (4 行)
  [PASS] 右上角显示当前用户  (演示用户)

[4] 任务面板（tab 切换 + 新建表单）
  [PASS] 筛选真的生效了（行数变化）  (全部 37 → 已完成 27)

[6] 运行时健康度
  [PASS] 无未捕获的 JS 异常
  [PASS] 无 console.error

====================================================================
 结果：23 通过 / 0 失败
====================================================================
```

四个刻意的设计：

- **检查元素高度**（`[146,146,146,146]`）而不只是「元素存在」。元素存在但高度为 0，用户看到的还是空白。
- **检查数值不是 `—`**。模板里的占位符是 `—`，接口挂了页面会一直显示 `—` 而**不会报错**。
- **监听 `pageerror` 与 `console.error`**。未捕获异常在 headless 里是静默的，不监听就等于没测。
- **崩溃时自动存 `99-failure.png`**。白屏排查最需要的就是「崩的那一刻长什么样」。

### 8.6 五层验证的关系

| 载体 | 断言数 | 验证什么 | 失败时说明 |
|---|---:|---|---|
| `./mvnw test` | 217 | 类与方法的行为契约（纯 Mockito，8 秒） | 后端逻辑错了 |
| `./mvnw verify` | +29 | 真实 MySQL + 真实 HTTP 的端到端契约 | 过滤器链 / JSON 字段名 / JPA SQL / 越权防护 坏了 |
| `tools/p4-e2e-test.py` | 86 | 真实实例 + 真实 MySQL 的端到端语义 | 集成层面错了 |
| `tools/p4-web-e2e.py` | 82 | 前端依赖的接口契约 | 前端会拿到坏数据 |
| `tools/screenshot.js` | 23 | 页面真的渲染出来了 | 前端会白屏 |

**关于「合计」要诚实说一句**：`mvn verify` 里那 29 项和 `p4-web-e2e.py` 的 82 项
**是同一批断言的两种载体** —— 集成测试就是把前端契约断言搬进了 Java、搬进了构建。
所以不能简单相加说「433 项」，去重后的**独立**断言是
**217 + 86 + 82 + 23 = 408 项**，集成测试是这 408 项里「接口契约」那部分的
**可重复执行版本**（能进 CI、能在本地 `mvn verify` 一次跑完、跑完自动清库）。

那为什么搬进 Java 之后**还留着** `p4-web-e2e.py`？因为它有两个集成测试替代不了的好处：
**① 它是独立的第二实现** —— 用 Python 的 `http.client` 手写请求，不复用 Java 侧任何
DTO / 序列化 / 客户端配置。如果 Java 侧的 `RestTemplate` 配置错了（比如那条 no-op
错误处理器写反），Java 测试会跟着一起错，而 Python 脚本不会。
**② 它能对着一个真正在跑的实例跑**（`run.cmd` 起的 8090），验的是「部署出来的东西」
而不是「测试里启动的东西」。

四者互相不可替代：一个接口可以「单测全绿 + 端到端全绿」但前端仍然白屏（字段名对不上），
也可以「接口契约全绿」但后端逻辑错（两端一起错）。

---

## 9. CI：每次 push 自动跑 `mvn verify`

`.github/workflows/ci.yml` —— push / PR 到 `main` 时自动执行：

```
services:
  mysql:8.0            # GitHub 托管，带 healthcheck，就绪后才开始跑测试
steps:
  checkout → setup-java(17, temurin, maven 缓存)
  → mysql < sql/schema.sql        # 建表
  → ./mvnw -B verify              # 217 单测 + 29 集成测试
  → upload-artifact（surefire/failsafe 报告，if: always()）
```

三个细节，外加一个差点漏掉的坑：

- **`if: always()` 上传报告**。测试失败时最需要报告，不加这句失败的那次反而没有报告。
- **`concurrency` + `cancel-in-progress`**。连续 push 时自动取消上一次，
  不然几次运行会同时抢 MySQL service，日志互相污染。
- **`options: --health-*`**。MySQL 容器起来到能接受连接有十几秒，
  没有 healthcheck 的话第一步 `mysql < schema.sql` 就会连接失败 —— 而且报的是
  「表建不上」，很容易误判成 SQL 有问题。

**④ 时区**：GitHub runner 的默认时区是 **UTC**，而测试用
`LocalDate.now()` 造数据、统计接口也用 `LocalDate.now()` 算「今天」。
如果 JVM 跑在 UTC 而本机在北京时间，那么**北京时间 00:00–08:00 这 8 小时里
「今天」会差一天** —— 测试造的数据落在「明天」，统计查不到，红得莫名其妙，
而且只有三分之一的运行会红。所以 workflow 里显式设了 `TZ: Asia/Shanghai`。

> 排查时确认了两件事：① 「今天」是在 **Java 侧**算的（`StatsService` 里
> `LocalDate.now()`），不是 SQL 的 `CURDATE()` —— 后者会跟随 MySQL 容器时区（UTC）；
> ② 所有时间列都是 `DATETIME(6)` / `DATE`，**没有 `TIMESTAMP`**
> （`TIMESTAMP` 会被 MySQL 按时区转换，`DATETIME` 不会），
> `created_at` 由 JPA 的 `@CreatedDate` 在 Java 侧填。
> 两条都成立，`TZ` 才是唯一需要固定的地方。

另外一件小事：`mvnw` / `run.sh` 的执行位以前**没有记进 git**（存的是 `100644`），
Linux 上 `./mvnw` 直接 `Permission denied` —— 而报错信息看起来像是脚本本身坏了。
现在两个文件都是 `100755`，CI 里还留了一句 `chmod +x` 兜底（Windows 上 clone
有时会丢执行位）。

---

## 10. 项目结构

```
exam-tracker/
├── run.cmd / run.sh              # 一条命令启动（Windows / Linux·macOS）
├── pom.xml                       # surefire 排除 *IT / failsafe 收 *IT
├── Dockerfile                    # 多阶段构建（未在真实 Docker 上验证，见 4.5）
├── docker-compose.yml            # app + mysql 一键起（同上）
├── .dockerignore
├── .github/
│   └── workflows/
│       └── ci.yml                # push / PR -> mvn verify（带 MySQL 8 service）
├── docs/
│   └── screenshots/              # README 第 2 节用的 8 张截图
├── sql/
│   ├── schema.sql                # 幂等建库建表 + 自检查询
│   └── seed.sql                  # 演示数据（相对今天生成、可重复灌）
├── tools/
│   ├── p4-e2e-test.py            # 端到端验证（86 项断言）
│   ├── p4-web-e2e.py             # 前端契约验证（82 项断言）
│   ├── screenshot.js             # 渲染自检 + 截图（23 项断言）
│   └── GenBcrypt.java            # 用项目自己的编码器生成 BCrypt 哈希
└── src/
    ├── main/
    │   ├── java/com/wpc725562/examtracker/
    │   │   ├── ExamTrackerApplication.java
    │   │   ├── common/           # ApiResponse PageResult ErrorCode BusinessException
    │   │   │                     # SortResolver GlobalExceptionHandler
    │   │   ├── config/           # SecurityConfig OpenApiConfig
    │   │   ├── controller/       # 6 个控制器
    │   │   ├── domain/           # 4 个实体 + 枚举 + BaseTimeEntity
    │   │   ├── dto/              # record 形式的请求/响应
    │   │   ├── repository/       # 4 个接口 + projection/（record 投影）
    │   │   ├── security/         # JwtService JwtAuthenticationFilter UserPrincipal …
    │   │   └── service/          # 5 个 Service + StreakCalculator + TaskSpecifications
    │   └── resources/
    │       ├── application.yml   # 全部可覆盖项都写成 ${ENV:default}
    │       └── static/           # 前端：index.html + app.js + style.css
    └── test/java/…
        ├── …（16 个单元测试类 / 217 个用例，纯 Mockito，`mvn test` 跑）
        └── it/ExamTrackerIT.java # 集成测试 29 个用例，真实 MySQL + 真实 HTTP，`mvn verify` 跑
```

### 关于 `tools/GenBcrypt.java`

种子数据里 `demo` 的密码哈希是用**项目自己的 `BCryptPasswordEncoder`** 生成的，
不是外部的 bcrypt 命令行工具。理由很朴素：让「生成哈希的实现」和「校验哈希的实现」
是同一个，就不存在两边对不上的可能。

它会**先自检再输出** —— `encode()` 返回 `null`，或者刚生成的哈希 `matches()` 不通过，
就直接抛异常、进程非 0 退出，免得把坏哈希写进 `sql/seed.sql`。

> 这里踩过一个值得记的坑：最初用 `jshell` 生成，classpath 里缺 `commons-logging` 时
> `new BCryptPasswordEncoder()` 会抛 `NoClassDefFoundError`，但 **jshell 只把错误打印出来、
> 然后继续往下执行** —— `hash` 保持 `null`，而脚本最后那行 `SELFCHECK=OK` 照样打印出来。
> 一个彻头彻尾的**假绿**。改成编译执行（`java -cp … tools/GenBcrypt.java`）之后，
> 异常会让进程真的非 0 退出，这种错误就不可能被漏掉。

---

## 11. 已知限制与下一步

**明确的限制（不是「以后再说」，是现在就没做）：**

- 没有刷新令牌（refresh token）。JWT 有效期 12 小时，过期需要重新登录。
  权衡：JWT 无法单独撤销，短有效期 + 不做 refresh 是当前最简单且安全的选择。
- 没有限流。登录接口面对暴力破解没有速率限制，生产环境应在网关层加。
- 统计接口没有缓存。数据量到十万级时 `subjectBoard` 的四个聚合查询会成为瓶颈，
  届时需要按 `(user_id, checkin_date)` 建汇总表或加 Redis 缓存。
- **集成测试连的是「外部数据库」，不自带。** 跑 `mvn verify` 前得先有一个能连的 MySQL
  （本地 3308 / CI 由 service 提供）。这是为了绕开「本机 Docker 起不来 → Testcontainers
  不可用」的取舍（见 8.2），代价是**新机器上 `mvn verify` 会红**，而 `mvn test` 永远绿。
  对「想先看看代码质量」的人，这个门槛是真实存在的。
- **集成测试用的不是测试专用库。** 它和演示数据共用同一个 `exam_tracker` 库，
  靠 `it_*` 用户名前缀 + `@AfterAll` 清理来隔离。好处是零配置，
  代价是**跑测试期间库里会短暂多出几个 `it_*` 用户**，而且如果 JVM 被强杀，
  残留数据要等下一次运行才会被清掉（`@AfterAll` 里那条 SQL 顺手清历史残留）。
- **前端没有覆盖交互逻辑的自动化测试**。`tools/screenshot.js` 验的是「页面渲染出来了」，
  验不了「点这个按钮应该发生什么」—— 状态流转、筛选、新建表单这些交互目前只在
  接口层面被 `tools/p4-web-e2e.py` 和集成测试间接覆盖，浏览器里真的点一遍还得靠人。
- 前端是手写 ES5，没有构建步骤也就没有类型检查、没有模块打包。
  代价是 `app.js` 613 行集中在一个文件里，再长就该拆了。
- 任务列表一次加载全部（37 条），没有分页。后端接口是支持分页的，
  前端为了简单没用 —— 任务到几百条时需要补上。

**下一步（按性价比排序）：**

1. **把 `tools/p4-e2e-test.py` 的 86 项也搬进 `*IT.java`** —— 现在搬进来的是
   `p4-web-e2e.py` 那批（接口契约）。`p4-e2e-test.py` 里还有一批集成测试没覆盖的：
   存活探针、Swagger UI / OpenAPI 文档可达、通配符转义、日志体检（无 `ERROR` 行）。
   搬完就能把「外部脚本」这一层彻底去掉。
2. **前端补交互测试**（Playwright 的 `@playwright/test` 可以直接复用
   `tools/screenshot.js` 里那段登录流程）。这是当前**唯一**没有人管的一层 ——
   其余四层都自动化了，只有「真的点一下」还得靠人。
3. 给集成测试换成 Testcontainers（等本机 Docker 能用之后）——
   这样 `mvn verify` 就能真正「一条命令、零前置」。
4. 任务列表接上后端已有的分页参数。
5. 统计接口加缓存（先测量，再优化）。

---

## 12. 作者

**wpc725562-dotcom** · AI Agent 开发者
仓库：<https://github.com/wpc725562-dotcom>

本项目为个人原创作品，从领域建模、分层设计到测试全部手写。
