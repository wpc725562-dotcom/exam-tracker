# exam-tracker · 备考任务追踪 API

> 面向备考者的**学习管理 REST API**：科目 → 每日任务 → 学习打卡 → 进度统计，一条闭环。
> 用 Spring Boot 3.5 / Java 17 从零手写，含 **217 个单元测试** 与 **86 项端到端断言**（全部实测通过）。

<p>
<img alt="Java" src="https://img.shields.io/badge/Java-17-007396">
<img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.5.14-6DB33F">
<img alt="MySQL" src="https://img.shields.io/badge/MySQL-8.0-4479A1">
<img alt="tests" src="https://img.shields.io/badge/tests-217%20passing-brightgreen">
<img alt="e2e" src="https://img.shields.io/badge/e2e-86%2F86%20passing-brightgreen">
</p>

---

## 日本語

exam-tracker は、資格試験の受験者向けの**学習管理 API** です。科目・日次タスク・
学習チェックイン・進捗統計を提供します。

- **技術スタック**：Java 17 / Spring Boot 3.5 / Spring Security + JWT / Spring Data JPA / MySQL 8 / Springdoc OpenAPI
- **設計方針**：すべてのクエリに `user_id` 条件を含めることで、ID を推測されても他人のデータに到達できないようにしています（IDOR 対策）
- **品質**：単体テスト **217 件**、エンドツーエンド検証 **86 項目**をすべてパス（実測ログを本 README に掲載）
- **同梱物**：Swagger UI（`/api/doc.html`）、建表 SQL（`sql/schema.sql`）、Dockerfile、docker-compose.yml

## English

exam-tracker is a **study-management REST API** for exam candidates: subjects, daily
tasks, study check-ins and progress statistics.

- **Stack**: Java 17, Spring Boot 3.5, Spring Security + JWT, Spring Data JPA, MySQL 8, Springdoc OpenAPI
- **Design**: every query is scoped by `user_id`, so guessing an ID never reaches another user's data (IDOR defence)
- **Quality**: **217 unit tests** and **86 end-to-end assertions**, all passing (measured output included below)
- **Ships with**: Swagger UI at `/api/doc.html`, DDL in `sql/schema.sql`, Dockerfile, docker-compose.yml

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

## 2. 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 语言 / 运行时 | Java 17（LTS） | 用 `record`、文本块、`switch` 表达式 |
| 框架 | Spring Boot **3.5.14** | Web / Validation / Actuator |
| 持久化 | Spring Data JPA + Hibernate | 复杂筛选走 `Specification` |
| 数据库 | MySQL **8.0** | 表结构由 `sql/schema.sql` 管理，应用侧 `ddl-auto: validate` |
| 安全 | Spring Security + **jjwt 0.12.6** | 无状态 JWT，BCrypt 存密码 |
| 文档 | Springdoc OpenAPI **2.8.17** | Swagger UI：`/api/doc.html` |
| 测试 | JUnit 5 + Mockito + AssertJ + MockMvc | 217 个用例 |
| 构建 | Maven（`./mvnw`，无需预装） | 打包出可执行 fat jar |

规模：**50 个主源文件 / 3,848 行**，**16 个测试文件 / 3,808 行**（测试与主代码接近 1:1）。

---

## 3. 快速开始

### 3.1 本地直接跑（推荐，本机实测过）

```bash
# 1) 建库建表（幂等，可重复执行）
mysql -h 127.0.0.1 -P 3308 -uroot -p < sql/schema.sql

# 2) 打包（会先跑 217 个单元测试）
./mvnw package

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
curl http://127.0.0.1:8090/api/health
# {"status":"ok"}
```

打开 <http://127.0.0.1:8090/api/doc.html> 是 Swagger UI。

**默认连的数据库**：`127.0.0.1:3308/exam_tracker`，账号 `dev` / `dev123456`。
不一样就用环境变量覆盖（见下表），**不需要改配置文件**。

### 3.2 全部可覆盖的环境变量

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

### 3.3 Docker

```bash
docker compose up -d --build
curl http://127.0.0.1:8090/api/health
```

compose 会另起一个 MySQL（映射到宿主机的 **13308**，不和工作区的 3308 撞），
并自动执行 `sql/schema.sql`。

> ⚠️ **诚实声明**：`Dockerfile` 与 `docker-compose.yml` **没有在真实 Docker 上构建验证过** ——
> 开发机的 Docker 起不来（`hypervisorlaunchtype=Off`，见工作区 `docs/runtime.md`）。
> 文件内容是按官方文档写的，但「能跑」请以你自己 `docker compose up` 的结果为准。
> 本 README 里所有「实测」字样指的都是 **3.1 的本地 jar 路线**。

---

## 4. API 一览

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

## 5. 架构与分层

```
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

---

## 6. 关键设计决定

这一节是面试里真正能展开的部分 —— 每条都是「不这么做会出什么问题」。

### 6.1 数据模型

| 决定 | 为什么 |
|---|---|
| **计划时长与实际时长分成两个字段** | 塞进一个字段就表达不了「计划 90 分钟、实际 20 分钟」，而那正是最该看见的信息 |
| **打卡记录可以挂在任务上，也可以不挂**（`checkin.task_id` 可空） | 随手翻笔记、听听力这类学习时间不属于任何任务 |
| **`checkin → task` 外键是 `ON DELETE SET NULL`，不是 `CASCADE`** | 删任务是「整理待办」，而「那天确实学了 90 分钟」是既成事实。级联删除会让用户删几个任务后发现累计时长和连续天数莫名其妙变少 |
| **`subject → task/checkin` 是 `CASCADE`** | 删科目是用户**显式确认过**的动作（要求 `force=true`），留一堆「属于已删科目」的孤儿数据没有意义 |
| **复合索引写成 `(user_id, plan_date)` 而不是 `(plan_date, user_id)`** | 本项目**每一次**查询都以 `user_id` 开头（数据隔离），列顺序反了索引就用不上 |
| **日期用 `DATE` 而不是时间戳** | 任务是按「天」规划的，带上时分秒只会引入时区歧义 |
| **枚举以字符串存**（`status` / `priority`） | 存序号的话，将来在枚举中间插一个值，历史数据全部错位 |

### 6.2 安全

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

### 6.3 正确性

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

### 6.4 可运维性

| 决定 | 为什么 |
|---|---|
| **`ddl-auto: validate`，表结构由 `sql/schema.sql` 管理** | `update` 会「顺手」改表，加字段时可能悄悄改掉列类型或丢索引；生产变更必须可审核、可回溯。`validate` 让实体与表对不上时**启动就失败** |
| **显式排除 `UserDetailsServiceAutoConfiguration`** | 否则 Spring Security 会生成一个随机密码并**打印在启动日志里**，还留下一个没人记得的登录入口 |
| **`logging.charset.console: UTF-8`** | JDK 17 在中文 Windows 上默认编码是 GBK，中文日志会变成 `δԤ���쳣` 这种乱码 —— 而且**不报错**，只是把排错时最有用的信息毁掉 |
| **`server.shutdown: graceful` + Dockerfile 用 `exec` 启动** | 让 SIGTERM 直接到 JVM，把手上正在处理的请求做完再退出 |
| **健康检查用 `/api/health` 而不是 `/actuator/health`** | 前者不查依赖。数据库短暂抖动时不应该让编排系统把所有实例同时判死 —— 那会引发雪崩 |
| **Actuator 只暴露 `health` / `info`** | `env` / `beans` 会漏出配置和内部结构 |

---

## 7. 测试与验证

### 7.1 单元测试：217 个，全部通过

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
| 18 | `StreakCalculatorTest` | 跨月 / 跨年 / 闰年 / 断签 / 「今天还没打卡」 |
| 18 | `TaskControllerTest` | **userId 来自 token 而不是请求参数**、状态码映射、异常不泄漏 |
| 16 | `AuthServiceTest` | 账号枚举防护、时间侧信道、唯一索引兜底、密码只存哈希 |
| 16 | `SubjectServiceTest` | 删除保护（409 / `force=true`）、删除顺序、颜色归一化 |
| 16 | `GlobalExceptionHandlerTest` | 12 个 handler 的状态码映射、**兜底不泄漏异常原文** |
| 15 | `ValidationTest` | 每个 DTO 的边界值（1/1440、50/51、`#RGB`/`#RRGGBB`…） |
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

### 7.2 端到端验证：86 项断言，全部通过

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

---

## 8. 项目结构

```
exam-tracker/
├── pom.xml
├── Dockerfile                    # 多阶段构建（未在真实 Docker 上验证，见 3.3）
├── docker-compose.yml            # app + mysql 一键起（同上）
├── .dockerignore
├── sql/
│   └── schema.sql                # 幂等建库建表 + 自检查询
├── tools/
│   └── p4-e2e-test.py            # 端到端验证脚本（86 项断言）
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
    │       └── application.yml   # 全部可覆盖项都写成 ${ENV:default}
    └── test/java/…               # 16 个测试类 / 217 个用例
```

---

## 9. 已知限制与下一步

**明确的限制（不是「以后再说」，是现在就没做）：**

- 没有刷新令牌（refresh token）。JWT 有效期 12 小时，过期需要重新登录。
  权衡：JWT 无法单独撤销，短有效期 + 不做 refresh 是当前最简单且安全的选择。
- 没有限流。登录接口面对暴力破解没有速率限制，生产环境应在网关层加。
- 统计接口没有缓存。数据量到十万级时 `subjectBoard` 的四个聚合查询会成为瓶颈，
  届时需要按 `(user_id, checkin_date)` 建汇总表或加 Redis 缓存。
- 没有集成测试（`*IT.java`）。当前 `surefire` 显式排除了它们，
  端到端验证靠 `tools/p4-e2e-test.py` 这个外部脚本承担 —— 好处是不依赖 Testcontainers，
  代价是它不在 `mvn verify` 里，需要单独跑。
- 没有前端。本项目只提供 API，Swagger UI 可以用来手工验证。

**下一步（按性价比排序）：**

1. 用 Testcontainers 把 `p4-e2e-test.py` 的断言搬进 `*IT.java`，让 `mvn verify` 一次跑完
2. 加 GitHub Actions：`mvn verify` + 端到端脚本
3. 统计接口加缓存（先测量，再优化）

---

## 10. 作者

**wpc725562-dotcom** · AI Agent 开发者
仓库：<https://github.com/wpc725562-dotcom>

本项目为个人原创作品，从领域建模、分层设计到测试全部手写。
