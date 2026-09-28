package com.wpc725562.examtracker.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端集成测试 —— 真实 MySQL + 真实 HTTP + 真实 Spring 上下文。
 *
 * <h2>为什么需要它（和单元测试的分工）</h2>
 * 217 个单元测试全部是纯 Mockito（不启动 Spring 容器），跑得飞快，但它们
 * <b>验不了</b>这些东西：
 * <ul>
 *   <li>Spring Security 的过滤器链是否真的按预期放行/拦截（单测里是手工塞的 SecurityContext）</li>
 *   <li>JSON 序列化后的<b>字段名</b> —— 前端按字段名取值，少一个就渲染成 undefined，
 *       而单测直接比对 Java 对象，看不到这一层</li>
 *   <li>JPA 生成的 SQL 是否真的能跑（单测里 Repository 是 mock 的）</li>
 *   <li>越权防护：A 用户真的拿不到 B 用户的数据（单测验的是「查询条件里带了 userId」，
 *       验不了「带上之后真的查不到」）</li>
 * </ul>
 *
 * <h2>为什么不用 Testcontainers</h2>
 * 开发机的 Docker 起不来（{@code dockerDesktopLinuxEngine} 管道不存在），
 * 用 Testcontainers 会导致<b>本地根本跑不了</b>这个测试 —— 那就失去了
 * 「{@code mvn verify} 一次跑完」的意义。改成连真实 MySQL：
 * <ul>
 *   <li>本地：工作区的便携版 MySQL（默认 {@code 127.0.0.1:3308}）</li>
 *   <li>CI：GitHub Actions 的 {@code services: mysql:8.0}</li>
 * </ul>
 * 两边都是 MySQL 8，语义一致；配置全部走 {@code application.yml} 里已有的
 * {@code ${DB_HOST:...}} 这类环境变量，所以这里一行数据库配置都不用写。
 *
 * <h2>数据从哪来</h2>
 * <b>测试自己造</b>，不依赖 {@code sql/seed.sql}。理由：种子数据是给「演示」用的，
 * 它的数字（4 个科目、连续 7 天）会随设计调整而变化，把断言钉在那些数字上
 * 会让测试和演示数据互相绑架。这里注册两个独立用户（{@code it_a_*} / {@code it_b_*}），
 * 自己建科目/任务/打卡，{@code @AfterAll} 再把自己删干净 —— 顺带修掉了
 * {@code tools/p4-e2e-test.py} 那个「每次跑都创建用户却不清理」的老毛病。
 *
 * <p>所以：跑之前数据库里<b>只需要有表</b>（{@code sql/schema.sql} 建过就行），
 * 有没有演示数据都无所谓。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("端到端集成测试（真实 MySQL + 真实 HTTP）")
class ExamTrackerIT {

    /** 本次运行的唯一后缀 —— 同名用户不会和上一轮的残留撞车。 */
    private static final String RUN = Long.toString(System.currentTimeMillis() % 100_000_000L);
    private static final String USER_A = "it_a_" + RUN;
    private static final String USER_B = "it_b_" + RUN;
    private static final String PASSWORD = "ItPass2026";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private RestTemplate http;

    // ---- 共享测试数据（@BeforeAll 里一次性准备好）----
    private String tokenA;
    private String tokenB;
    private final List<Long> subjectIds = new ArrayList<>();
    private Long taskId;          // A 的一个 TODO 任务，用于状态流转
    private Long checkinSubjectId; // 专门用来造打卡的科目（后面会被删除保护测试吃掉）

    // =========================================================================
    //  准备 / 清理
    // =========================================================================

    @BeforeAll
    void setUp() {
        // 自己构造 RestTemplate 而不是注入自动配置的 TestRestTemplate，有三个理由：
        //
        //   ① rootUri 里要带上 context-path（/api），自己拼最不容易出错。
        //   ② 必须显式禁用代理 —— 本机环境有系统代理，它会把 127.0.0.1 的请求
        //      也劫持走，表现为「服务明明在跑却连不上」。
        //   ③ 错误处理器必须换成 no-op：默认的 DefaultResponseErrorHandler 遇到
        //      4xx/5xx 会抛 HttpClientErrorException，而本测试有大量断言是
        //      「这里应当返回 401 / 404 / 409 / 400」—— 抛异常就拿不到状态码了。
        //
        // ★ 为什么用 JdkClientHttpRequestFactory 而不是 SimpleClientHttpRequestFactory：
        //   后者基于 HttpURLConnection，而 **HttpURLConnection 不支持 PATCH**
        //   （只认 GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE），一调就抛
        //   `ProtocolException: Invalid HTTP method: PATCH`。
        //   本项目多处用 PATCH（改任务状态、改资料），所以必须换成
        //   java.net.http.HttpClient —— 它原生支持 PATCH，而且不用引 Apache HttpClient。
        HttpClient jdkClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(ProxySelector.of(null))   // 显式不使用任何代理
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdkClient);
        factory.setReadTimeout(Duration.ofSeconds(30));

        RestTemplate rt = new RestTemplate(factory);
        rt.setUriTemplateHandler(new DefaultUriBuilderFactory("http://127.0.0.1:" + port + "/api"));
        // 只覆写 hasError、**不实现 handleError**：后者在 Spring 里已被标记为
        // 「deprecated and marked for removal」，自己实现它会在编译时产生弃用告警。
        // 继承 DefaultResponseErrorHandler 就绕开了 —— hasError 返回 false 之后
        // handleError 永远不会被调用，继承来的那个实现是什么样都无所谓。
        rt.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;   // 永不视为错误 -> 永不抛异常，状态码交给断言去判
            }
        });
        this.http = rt;

        // 注册两个用户（A 是被测主体，B 专门用来验数据隔离）
        assertThat(register(USER_A, PASSWORD).status()).as("注册用户 A").isEqualTo(201);
        assertThat(register(USER_B, PASSWORD).status()).as("注册用户 B").isEqualTo(201);

        tokenA = login(USER_A, PASSWORD);
        tokenB = login(USER_B, PASSWORD);
        assertThat(tokenA).as("A 登录拿到 token").isNotBlank();
        assertThat(tokenB).as("B 登录拿到 token").isNotBlank();

        // A 建 4 个科目（前端的「四科看板」正好是 4 行）
        subjectIds.add(createSubject("数学", "#4F46E5", 420));
        subjectIds.add(createSubject("英语", "#D85A30", 300));
        subjectIds.add(createSubject("计算机", "#1D9E75", 480));
        subjectIds.add(createSubject("语文", "#BA7517", 180));
        checkinSubjectId = subjectIds.get(3);

        // 6 个任务：3 个今天（1 完成 / 1 跳过 / 1 待办），其余分布在过去几天
        LocalDate today = LocalDate.now();
        createTask(subjectIds.get(0), "积分换元法 20 题", today, 90, "HIGH");
        taskId = createTask(subjectIds.get(0), "二重积分计算", today, 75, "MEDIUM");
        createTask(subjectIds.get(1), "完形填空 2 篇", today, 45, "LOW");
        createTask(subjectIds.get(2), "事务与隔离级别", today.minusDays(1), 80, "HIGH");
        createTask(subjectIds.get(2), "JVM 内存模型笔记", today.minusDays(2), 60, "MEDIUM");
        createTask(subjectIds.get(1), "作文素材整理", today.minusDays(3), 40, "LOW");

        // 打卡：今天 150 分钟（保证 todayActualMinutes > 0 且连续天数 > 0），
        // 再加前两天，形成 3 天连续
        checkin(subjectIds.get(0), today, 90, "上午两小时");
        checkin(subjectIds.get(1), today, 60, "背单词 + 阅读");
        checkin(subjectIds.get(0), today.minusDays(1), 75, null);
        checkin(subjectIds.get(2), today.minusDays(2), 120, "看视频课");

        // 把第 1 个任务标成完成、第 3 个标成跳过 —— 让状态筛选有东西可筛
        Res r1 = call(HttpMethod.PATCH, "/tasks/" + taskId + "/status", Map.of("status", "DONE"), tokenA);
        assertThat(r1.status()).as("准备数据：标记任务完成").isEqualTo(200);
        taskId = createTask(subjectIds.get(0), "错题重做：级数收敛", today, 50, "HIGH"); // 留一个 TODO 给状态流转测试
    }

    @AfterAll
    void tearDown() {
        // 精确删自己创建的两个用户；顺手把历史上失败的运行留下的 it_* 一起收掉。
        // 用 LEFT(username,3) 而不是 LIKE 'it_%' —— SQL 里 _ 是单字符通配符，
        // LIKE 'it_%' 会连 'itX...' 一起匹配掉。
        int removed = jdbc.update("DELETE FROM app_user WHERE LEFT(username, 3) = 'it_'");
        System.out.println("[ExamTrackerIT] 清理了 " + removed + " 个测试用户（及其级联数据）");
    }

    // =========================================================================
    //  1. 静态资源 —— 登录页必须能匿名打开
    // =========================================================================

    @ParameterizedTest(name = "GET {0} 匿名可达且类型正确")
    @ValueSource(strings = {"/", "/index.html", "/app.js", "/style.css"})
    @DisplayName("1. 静态资源匿名可达")
    void staticResourcesArePubliclyReachable(String path) {
        ResponseEntity<String> res = http.getForEntity(path, String.class);

        assertThat(res.getStatusCode().value()).as("状态码").isEqualTo(200);
        String ctype = res.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        assertThat(ctype).as("Content-Type").isNotNull();
        assertThat(ctype).as("Content-Type 应与后缀匹配").satisfiesAnyOf(
                c -> assertThat(c).contains("html"),
                c -> assertThat(c).contains("javascript"),
                c -> assertThat(c).contains("css"));
        assertThat(res.getBody()).as("响应体不应为空").isNotBlank();
    }

    @Test
    @DisplayName("1b. 静态资源放行不影响安全边界（业务接口仍要认证）")
    void staticWhitelistDoesNotOpenBusinessApis() {
        // 这条是回归护栏：往 PUBLIC_PATHS 里加路径时很容易顺手加多了，
        // 比如把 "/**" 或 "/tasks" 也放进去。断言业务接口仍然是 401。
        assertThat(call(HttpMethod.GET, "/tasks", null, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/stats/overview", null, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/subjects", null, null).status()).isEqualTo(401);
    }

    // =========================================================================
    //  2. 认证契约
    // =========================================================================

    @Test
    @DisplayName("2. 登录响应字段（前端登录后要存 token、显示用户名）")
    void loginContract() {
        Res res = call(HttpMethod.POST, "/auth/login",
                Map.of("username", USER_A, "password", PASSWORD), null);

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.code()).as("业务码").isZero();
        assertThat(res.at("/data/token").asText()).as("token 非空").isNotBlank();
        assertThat(res.at("/data/tokenType").asText()).as("tokenType").isEqualTo("Bearer");
        assertThat(res.at("/data/expiresIn").asInt()).as("expiresIn 为正").isPositive();
        assertThat(res.at("/data/user/id").asLong()).as("user.id").isPositive();
        assertThat(res.at("/data/user/username").asText()).as("user.username").isEqualTo(USER_A);
    }

    @Test
    @DisplayName("2b. 错误密码 401，且不泄漏「用户是否存在」")
    void wrongPasswordIs401() {
        assertThat(call(HttpMethod.POST, "/auth/login",
                Map.of("username", USER_A, "password", "definitely-wrong"), null).status())
                .isEqualTo(401);

        // 账号枚举防护：不存在的用户与密码错误，返回**同一条消息**
        Res ghost = call(HttpMethod.POST, "/auth/login",
                Map.of("username", "it_ghost_" + RUN, "password", PASSWORD), null);
        assertThat(ghost.status()).as("不存在的用户").isEqualTo(401);
    }

    @Test
    @DisplayName("2c. 注册校验：重名 409、弱密码 400、非法用户名 400")
    void registerValidation() {
        assertThat(register(USER_A, PASSWORD).status()).as("重名").isEqualTo(409);
        assertThat(register("it_weak_" + RUN, "123").status()).as("密码过短").isEqualTo(400);
        assertThat(register("ab", PASSWORD).status()).as("用户名过短").isEqualTo(400);
        assertThat(register("it bad name " + RUN, PASSWORD).status()).as("含空格").isEqualTo(400);
    }

    @Test
    @DisplayName("2d. /auth/me：未设考试日期时字段不出现，设了则算出差值")
    void meContract() {
        Res res = call(HttpMethod.GET, "/auth/me", null, tokenA);

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.at("/data/username").asText()).isEqualTo(USER_A);

        // 注册时没传 examDate -> 值为 null。application.yml 配了
        // `default-property-inclusion: non_null`，所以这两个字段**本就不该出现在 JSON 里**。
        // 前端的写法是 `if (data.examDate) {...}`，字段缺失是预期行为，不是缺陷。
        assertThat(res.has("/data/examDate"))
                .as("未设考试日期时，null 字段不应被序列化出来").isFalse();

        // 反过来验证：真的设了考试日期时，字段会出现且倒计时被算出来
        String withExam = "it_c_" + RUN;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", withExam);
        body.put("password", PASSWORD);
        body.put("nickname", withExam);
        body.put("examDate", LocalDate.now().plusDays(100).toString());
        assertThat(call(HttpMethod.POST, "/auth/register", body, null).status())
                .as("注册一个带考试日期的用户").isEqualTo(201);

        Res me = call(HttpMethod.GET, "/auth/me", null, login(withExam, PASSWORD));
        assertThat(me.at("/data/examDate").asText())
                .as("examDate 应当出现").isEqualTo(LocalDate.now().plusDays(100).toString());
        assertThat(me.at("/data/daysUntilExam").asInt())
                .as("倒计时应当被算出来").isPositive();
    }

    @Test
    @DisplayName("2e. 未认证访问返回 JSON 格式的 401（不是 HTML 重定向）")
    void unauthenticatedIsJson401() {
        ResponseEntity<String> res = http.getForEntity("/tasks", String.class);

        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                .as("必须是 JSON，不能是 text/html 登录页").contains("json");
    }

    // =========================================================================
    //  3. 科目契约
    // =========================================================================

    @Test
    @DisplayName("3. 科目列表返回自己建的 4 个，字段齐全")
    void subjectListContract() {
        Res res = call(HttpMethod.GET, "/subjects", null, tokenA);

        assertThat(res.status()).isEqualTo(200);
        JsonNode list = res.at("/data");
        assertThat(list.isArray()).as("data 是数组").isTrue();
        assertThat(list.size()).as("自己建了 4 个科目").isEqualTo(4);

        JsonNode first = list.get(0);
        assertThat(first.has("id")).isTrue();
        assertThat(first.has("name")).isTrue();
        assertThat(first.has("color")).isTrue();
        assertThat(first.has("targetMinutesPerWeek")).isTrue();
        assertThat(first.has("sortOrder")).isTrue();

        assertThat(first.get("color").asText())
                .as("color 是 #RRGGBB（前端直接当 CSS 用）")
                .matches("#[0-9A-Fa-f]{6}");
    }

    @Test
    @DisplayName("3b. 科目重名被拒绝（同一用户下）")
    void duplicateSubjectRejected() {
        assertThat(call(HttpMethod.POST, "/subjects",
                Map.of("name", "数学", "color", "#123456", "targetMinutesPerWeek", 100), tokenA).status())
                .as("A 已经有「数学」了").isEqualTo(409);

        // B 用同一个名字应该成功 —— 唯一约束是「同一用户内」，不是全局
        Res created = call(HttpMethod.POST, "/subjects",
                Map.of("name", "数学", "color", "#123456", "targetMinutesPerWeek", 100), tokenB);
        assertThat(created.status())
                .as("B 建同名科目应当成功（约束是按用户隔离的）").isEqualTo(201);

        // ★ 用完就删。用例 9 要断言「B 的科目是空的」，如果这条把 B 的科目留在这儿，
        //   用例 9 的结果就取决于「谁先跑」—— JUnit 不保证方法执行顺序，
        //   这是最隐蔽的一类 flaky：单独跑全绿，整包跑随机红。
        Long bId = created.at("/data/id").asLong();
        assertThat(call(HttpMethod.DELETE, "/subjects/" + bId, null, tokenB).status())
                .as("清理 B 刚建的科目").isEqualTo(200);
    }

    // =========================================================================
    //  4. 任务契约
    // =========================================================================

    @Test
    @DisplayName("4. 任务分页外壳字段齐全")
    void taskPageEnvelope() {
        Res res = call(HttpMethod.GET, "/tasks?page=1&size=50", null, tokenA);

        assertThat(res.status()).isEqualTo(200);
        for (String f : List.of("items", "page", "size", "total", "totalPages")) {
            assertThat(res.has("/data/" + f)).as("分页字段 " + f).isTrue();
        }
        assertThat(res.at("/data/items").size()).as("返回了任务").isPositive();
    }

    @Test
    @DisplayName("4b. 任务字段齐全 —— 少一个前端就渲染成 undefined")
    void taskFieldsContract() {
        Res res = call(HttpMethod.GET, "/tasks?page=1&size=50", null, tokenA);
        JsonNode t = res.at("/data/items").get(0);

        // 这 8 个字段是 app.js 里直接拼进 HTML 的，任何一个缺失都会在页面上
        // 变成 "undefined" —— 而接口本身返回 200，只测状态码根本发现不了。
        for (String f : List.of("id", "subjectId", "subjectName", "title",
                "planDate", "planMinutes", "priority", "status")) {
            assertThat(t.has(f)).as("任务字段 " + f).isTrue();
        }
        assertThat(t.get("subjectName").asText())
                .as("subjectName 必须有值（列表显示科目名而不是 id）").isNotBlank();
        assertThat(t.get("priority").asText()).isIn("HIGH", "MEDIUM", "LOW");
        assertThat(t.get("status").asText()).isIn("TODO", "DONE", "SKIPPED");
    }

    @Test
    @DisplayName("4c. 按状态筛选真的生效")
    void taskStatusFilter() {
        JsonNode done = call(HttpMethod.GET, "/tasks?page=1&size=50&status=DONE", null, tokenA)
                .at("/data/items");
        assertThat(done.size()).as("有已完成的任务").isPositive();
        done.forEach(t -> assertThat(t.get("status").asText()).isEqualTo("DONE"));

        JsonNode skipped = call(HttpMethod.GET, "/tasks?page=1&size=50&status=SKIPPED", null, tokenA)
                .at("/data/items");
        skipped.forEach(t -> assertThat(t.get("status").asText()).isEqualTo("SKIPPED"));
    }

    @Test
    @DisplayName("4d. 非法枚举 / 非法分页参数 -> 400 而不是 500")
    void taskInvalidParams() {
        assertThat(call(HttpMethod.GET, "/tasks?status=NOT_A_STATUS", null, tokenA).status())
                .as("非法状态枚举").isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/tasks?size=99999", null, tokenA).status())
                .as("size 超过单页上限").isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/tasks?sortBy=; DROP TABLE task", null, tokenA).status())
                .as("排序字段不在白名单（注入尝试）").isEqualTo(400);
    }

    // =========================================================================
    //  5. 统计契约
    // =========================================================================

    @Test
    @DisplayName("5. 仪表盘总览：四个卡片读的字段全部存在")
    void overviewContract() {
        Res res = call(HttpMethod.GET, "/stats/overview", null, tokenA);

        assertThat(res.status()).isEqualTo(200);
        for (String f : List.of(
                "todayTotalTasks", "todayDoneTasks", "todayPendingTasks", "todaySkippedTasks",
                "todayCompletionRate", "todayPlannedMinutes", "todayActualMinutes",
                "currentStreakDays", "longestStreakDays", "totalCheckinDays", "lastCheckinDate",
                "totalTasks", "totalDoneTasks", "totalActualMinutes")) {
            assertThat(res.has("/data/" + f)).as("overview." + f).isTrue();
        }
    }

    @Test
    @DisplayName("5b. 总览的数值自洽（不是靠种子数据，而是靠本测试自己造的数据）")
    void overviewNumbersAreConsistent() {
        JsonNode ov = call(HttpMethod.GET, "/stats/overview", null, tokenA).at("/data");

        // 打卡相关的量可以钉精确值：只有 @BeforeAll 里那 4 条打卡，
        // 别的测试即使临时建打卡，也会随科目级联删除，净变化为 0。
        assertThat(ov.get("todayActualMinutes").asInt())
                .as("今天打卡 90+60=150 分钟").isEqualTo(150);
        assertThat(ov.get("totalActualMinutes").asInt())
                .as("累计 90+60+75+120=345 分钟").isEqualTo(345);
        assertThat(ov.get("currentStreakDays").asInt())
                .as("今天/昨天/前天都有打卡 -> 连续 3 天").isEqualTo(3);
        assertThat(ov.get("totalCheckinDays").asInt()).as("3 个不同的打卡日").isEqualTo(3);
        assertThat(ov.get("longestStreakDays").asInt())
                .as("历史最长不小于当前").isGreaterThanOrEqualTo(ov.get("currentStreakDays").asInt());

        // 任务数是**聚合量**：别的测试会临时建/删任务，钉具体数字就等于
        // 给测试之间埋了隐式顺序依赖。所以这里改钉「不变式 + 下界」。
        int today = ov.get("todayTotalTasks").asInt();
        int done = ov.get("todayDoneTasks").asInt();
        int pending = ov.get("todayPendingTasks").asInt();
        int skipped = ov.get("todaySkippedTasks").asInt();
        assertThat(today).as("今日任务数 = 完成 + 待办 + 跳过").isEqualTo(done + pending + skipped);
        assertThat(ov.get("totalTasks").asInt())
                .as("至少是 @BeforeAll 造的那 7 个").isGreaterThanOrEqualTo(7);
        assertThat(done).as("今天至少有 1 个已完成").isGreaterThanOrEqualTo(1);
        assertThat(ov.get("todayCompletionRate").asDouble()).as("完成率在 0~1").isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("6. 四科看板：外壳 + 每行 12 个字段")
    void subjectBoardContract() {
        Res res = call(HttpMethod.GET, "/stats/subjects?days=7", null, tokenA);

        assertThat(res.status()).isEqualTo(200);
        for (String f : List.of("periodDays", "from", "to", "periodTotalMinutes",
                "periodTargetMinutes", "overallAchievementRate", "subjects")) {
            assertThat(res.has("/data/" + f)).as("board." + f).isTrue();
        }

        JsonNode rows = res.at("/data/subjects");
        assertThat(rows.size()).as("4 个科目 -> 4 行").isEqualTo(4);

        JsonNode row = rows.get(0);
        for (String f : List.of("id", "name", "color", "targetMinutesPerWeek", "taskTotal",
                "taskDone", "taskPending", "completionRate", "periodMinutes",
                "targetMinutesInPeriod", "achievementRate", "lastCheckinDate")) {
            assertThat(row.has(f)).as("看板行字段 " + f).isTrue();
        }
    }

    @Test
    @DisplayName("6b. 看板窗口天数回显，且超上限 -> 400")
    void boardPeriodDays() {
        assertThat(call(HttpMethod.GET, "/stats/subjects?days=30", null, tokenA).at("/data/periodDays").asInt())
                .as("回显请求的窗口天数").isEqualTo(30);
        assertThat(call(HttpMethod.GET, "/stats/subjects?days=9999", null, tokenA).status())
                .as("days 上限 365").isEqualTo(400);
    }

    // =========================================================================
    //  7. 打卡趋势（柱状图数据源）
    // =========================================================================

    @Test
    @DisplayName("7. 按天汇总：前端补 0 之前拿到的原始数据")
    void dailyTrendContract() {
        LocalDate today = LocalDate.now();
        String from = today.minusDays(13).toString();
        String to = today.toString();

        Res res = call(HttpMethod.GET, "/checkins/daily?from=" + from + "&to=" + to, null, tokenA);

        assertThat(res.status()).isEqualTo(200);
        JsonNode daily = res.at("/data");
        assertThat(daily.isArray()).isTrue();
        assertThat(daily.size()).as("本测试造了 3 个打卡日").isEqualTo(3);

        daily.forEach(d -> {
            assertThat(d.has("date")).as("每条含 date").isTrue();
            assertThat(d.has("minutes")).as("每条含 minutes").isTrue();
            String date = d.get("date").asText();
            assertThat(date).as("日期在请求区间内").isBetween(from, to);
        });
    }

    // =========================================================================
    //  8. 写操作
    // =========================================================================

    @Test
    @DisplayName("8. 状态流转：TODO -> DONE 写上 completedAt，改回 TODO 又清空")
    void taskStatusTransition() {
        Long id = createTask(subjectIds.get(1), "状态流转专用", LocalDate.now(), 30, "LOW");

        Res done = call(HttpMethod.PATCH, "/tasks/" + id + "/status", Map.of("status", "DONE"), tokenA);
        assertThat(done.status()).isEqualTo(200);
        assertThat(done.at("/data/status").asText()).isEqualTo("DONE");
        assertThat(done.at("/data/completedAt").isNull())
                .as("完成后 completedAt 必须被写上").isFalse();

        Res back = call(HttpMethod.PATCH, "/tasks/" + id + "/status", Map.of("status", "TODO"), tokenA);
        assertThat(back.at("/data/status").asText()).isEqualTo("TODO");
        assertThat(back.at("/data/completedAt").isNull())
                .as("改回 TODO 后 completedAt 必须清空（否则统计会把未完成算成完成）").isTrue();

        // ★ 清理：A 的任务总数是 5b / 9c 会读的量，留一条在这里会让那两个用例的
        //   结果取决于本用例是否先跑（隐式顺序依赖）。
        assertThat(call(HttpMethod.DELETE, "/tasks/" + id, null, tokenA).status())
                .as("清理状态流转用的任务").isEqualTo(200);
    }

    @Test
    @DisplayName("8b. 写操作的错误码：改不存在的任务 404、非法时长 400")
    void writeOperationErrors() {
        assertThat(call(HttpMethod.PATCH, "/tasks/999999/status", Map.of("status", "DONE"), tokenA).status())
                .isEqualTo(404);
        assertThat(call(HttpMethod.POST, "/tasks",
                Map.of("subjectId", subjectIds.get(0), "title", "非法时长",
                        "planDate", LocalDate.now().toString(), "planMinutes", 0), tokenA).status())
                .as("计划时长 0 应当 400").isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/tasks",
                Map.of("subjectId", subjectIds.get(0), "title", "非法时长",
                        "planDate", LocalDate.now().toString(), "planMinutes", 1441), tokenA).status())
                .as("超过 1440 分钟应当 400").isEqualTo(400);
    }

    @Test
    @DisplayName("8c. 打卡不能落在未来日期")
    void checkinFutureDateRejected() {
        Res res = call(HttpMethod.POST, "/checkins",
                Map.of("subjectId", subjectIds.get(0),
                        "checkinDate", LocalDate.now().plusDays(1).toString(),
                        "actualMinutes", 30), tokenA);
        assertThat(res.status()).as("未来日期打卡应当被拒绝").isEqualTo(400);
    }

    // =========================================================================
    //  9. 数据隔离（越权防护）—— 安全相关，最该在 CI 里跑
    // =========================================================================

    @Test
    @DisplayName("9. 数据隔离：B 看不到 A 的数据，且看得见自己的")
    void userBCannotSeeUserAData() {
        // ---- 方向一：B 自己没有造过任何数据，三个读接口都应当是空的 ----
        assertThat(call(HttpMethod.GET, "/subjects", null, tokenB).at("/data").size())
                .as("B 看不到 A 的 4 个科目").isZero();
        assertThat(call(HttpMethod.GET, "/tasks?page=1&size=50", null, tokenB).at("/data/total").asInt())
                .as("B 看不到 A 的 7 个任务").isZero();
        assertThat(call(HttpMethod.GET, "/stats/overview", null, tokenB).at("/data/totalTasks").asInt())
                .as("B 的统计不串 A 的数据").isZero();

        // ---- 方向二（不能省）：只断言「看不见」是**假绿** ----
        // 如果查询条件写错成 `WHERE 1=0`、或者压根忘了带 userId 而是返回空集，
        // 上面三条一样会通过。所以必须再验证「B 看得见自己的」，且「A 看不见 B 的」。
        Long bSubject = createSubjectAs(tokenB, "B 的专属科目", "#010203", 60);
        try {
            JsonNode bList = call(HttpMethod.GET, "/subjects", null, tokenB).at("/data");
            assertThat(bList.size()).as("B 看得见自己刚建的那 1 个").isEqualTo(1);
            assertThat(bList.get(0).get("id").asLong()).as("就是它").isEqualTo(bSubject);

            JsonNode aList = call(HttpMethod.GET, "/subjects", null, tokenA).at("/data");
            List<Long> aIds = new ArrayList<>();
            aList.forEach(n -> aIds.add(n.get("id").asLong()));
            assertThat(aIds).as("A 的科目里不该混进 B 的").doesNotContain(bSubject);
            assertThat(aList.size()).as("A 的科目数不受 B 影响").isEqualTo(4);
        } finally {
            // 用完删掉：不给别的用例留下「B 有几个科目」这种会漂的状态
            call(HttpMethod.DELETE, "/subjects/" + bSubject, null, tokenB);
        }
    }

    @Test
    @DisplayName("9b. B 按 id 直接访问 A 的资源 -> 404（不是 403，避免暴露资源是否存在）")
    void idorReturnsNotFound() {
        Long aSubject = subjectIds.get(0);
        Long aTask = taskId;

        assertThat(call(HttpMethod.GET, "/subjects/" + aSubject, null, tokenB).status())
                .as("读 A 的科目").isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/tasks/" + aTask, null, tokenB).status())
                .as("读 A 的任务").isEqualTo(404);
        assertThat(call(HttpMethod.PATCH, "/tasks/" + aTask + "/status",
                Map.of("status", "DONE"), tokenB).status())
                .as("改 A 的任务").isEqualTo(404);
        assertThat(call(HttpMethod.DELETE, "/tasks/" + aTask, null, tokenB).status())
                .as("删 A 的任务").isEqualTo(404);
    }

    @Test
    @DisplayName("9c. B 发起越权写操作后，A 的数据分毫未动")
    void userADataUntouchedAfterB() {
        // 先记基线，**不钉死数字** —— 别的用例会临时建/删任务，钉死就等于依赖执行顺序。
        // 这里真正要验的是「B 动过之后有没有变」。
        JsonNode before = call(HttpMethod.GET, "/tasks?page=1&size=50", null, tokenA).at("/data");
        int totalBefore = before.get("total").asInt();
        int actualBefore = call(HttpMethod.GET, "/stats/overview", null, tokenA)
                .at("/data/todayActualMinutes").asInt();

        // B 拿着自己**合法**的 token，去改 / 删 A 的任务
        assertThat(call(HttpMethod.PATCH, "/tasks/" + taskId + "/status",
                Map.of("status", "DONE"), tokenB).status())
                .as("B 改 A 的任务状态").isEqualTo(404);
        assertThat(call(HttpMethod.DELETE, "/tasks/" + taskId, null, tokenB).status())
                .as("B 删 A 的任务").isEqualTo(404);

        // B 试图把打卡挂到 A 的科目上（走 SubjectService.requireSubject -> 404）
        assertThat(call(HttpMethod.POST, "/checkins",
                Map.of("subjectId", subjectIds.get(0),
                        "checkinDate", LocalDate.now().toString(),
                        "actualMinutes", 999), tokenB).status())
                .as("B 往 A 的科目上打卡").isEqualTo(404);

        // ---- 断言 A 的数据与基线完全一致 ----
        JsonNode after = call(HttpMethod.GET, "/tasks?page=1&size=50", null, tokenA).at("/data");
        assertThat(after.get("total").asInt()).as("A 的任务总数没变").isEqualTo(totalBefore);

        JsonNode t = call(HttpMethod.GET, "/tasks/" + taskId, null, tokenA).at("/data");
        assertThat(t.get("title").asText()).as("A 的任务标题未变").isEqualTo("错题重做：级数收敛");
        assertThat(t.get("status").asText()).as("A 的任务状态未被 B 改动").isEqualTo("TODO");
        assertThat(t.get("planMinutes").asInt()).as("A 的任务时长未变").isEqualTo(50);

        assertThat(call(HttpMethod.GET, "/stats/overview", null, tokenA)
                .at("/data/todayActualMinutes").asInt())
                .as("A 的打卡分钟数没被 B 那次越权打卡污染").isEqualTo(actualBefore);
    }

    // =========================================================================
    //  10. 删除保护
    // =========================================================================

    @Test
    @DisplayName("10. 有数据的科目默认拒绝删除（409），force=true 才级联")
    void subjectDeleteGuard() {
        // 自己造一个「有数据」的科目，不去动共享的那几个
        Long id = createSubject("删除保护用", "#888888", 100);
        createTask(id, "挂在它下面的任务", LocalDate.now(), 30, "LOW");
        checkin(id, LocalDate.now(), 25, null);

        Res guarded = call(HttpMethod.DELETE, "/subjects/" + id, null, tokenA);
        assertThat(guarded.status()).as("默认拒绝").isEqualTo(409);
        assertThat(guarded.at("/message").asText())
                .as("提示里要说清有多少条关联数据").contains("任务").contains("打卡");

        Res forced = call(HttpMethod.DELETE, "/subjects/" + id + "?force=true", null, tokenA);
        assertThat(forced.status()).as("force=true 才允许").isEqualTo(200);

        assertThat(call(HttpMethod.GET, "/subjects/" + id, null, tokenA).status())
                .as("删完确实没了").isEqualTo(404);
    }

    @Test
    @DisplayName("10b. 空科目可以直接删，不需要 force")
    void emptySubjectDeletesFreely() {
        Long id = createSubject("空的科目", "#999999", 100);
        assertThat(call(HttpMethod.DELETE, "/subjects/" + id, null, tokenA).status()).isEqualTo(200);
    }

    // =========================================================================
    //  工具方法
    // =========================================================================

    /** 一次 HTTP 往返的结果：状态码 + 解析后的 JSON。 */
    record Res(int status, JsonNode body) {
        JsonNode at(String pointer) {
            JsonNode n = body.at(pointer);
            return n.isMissingNode() ? NullNode.getInstance() : n;
        }

        boolean has(String pointer) {
            return !body.at(pointer).isMissingNode();
        }

        int code() {
            return body.path("code").asInt(-1);
        }
    }

    private Res call(HttpMethod method, String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        if (token != null) {
            headers.setBearerAuth(token);
        }

        ResponseEntity<String> res = http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        JsonNode json;
        try {
            String raw = res.getBody();
            json = (raw == null || raw.isBlank()) ? NullNode.getInstance() : mapper.readTree(raw);
        } catch (Exception e) {
            json = NullNode.getInstance();
        }
        return new Res(res.getStatusCode().value(), json);
    }

    private Res register(String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("nickname", username);
        return call(HttpMethod.POST, "/auth/register", body, null);
    }

    private String login(String username, String password) {
        Res res = call(HttpMethod.POST, "/auth/login",
                Map.of("username", username, "password", password), null);
        assertThat(res.status()).as("登录 " + username).isEqualTo(200);
        return res.at("/data/token").asText();
    }

    private Long createSubject(String name, String color, int weeklyMinutes) {
        return createSubjectAs(tokenA, name, color, weeklyMinutes);
    }

    /** 用指定用户的身份建科目 —— 数据隔离测试需要给 B 造点「自己的」数据。 */
    private Long createSubjectAs(String token, String name, String color, int weeklyMinutes) {
        Res res = call(HttpMethod.POST, "/subjects",
                Map.of("name", name, "color", color, "targetMinutesPerWeek", weeklyMinutes), token);
        assertThat(res.status()).as("建科目 " + name).isEqualTo(201);
        return res.at("/data/id").asLong();
    }

    private Long createTask(Long subjectId, String title, LocalDate date, int minutes, String priority) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subjectId", subjectId);
        body.put("title", title);
        body.put("planDate", date.toString());
        body.put("planMinutes", minutes);
        body.put("priority", priority);
        Res res = call(HttpMethod.POST, "/tasks", body, tokenA);
        assertThat(res.status()).as("建任务 " + title).isEqualTo(201);
        return res.at("/data/id").asLong();
    }

    private void checkin(Long subjectId, LocalDate date, int minutes, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subjectId", subjectId);
        body.put("checkinDate", date.toString());
        body.put("actualMinutes", minutes);
        if (note != null) {
            body.put("note", note);
        }
        assertThat(call(HttpMethod.POST, "/checkins", body, tokenA).status())
                .as("打卡 " + date + " " + minutes + " 分钟").isEqualTo(201);
    }
}
