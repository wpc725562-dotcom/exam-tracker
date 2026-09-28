# AI 层改造方案：自然语言查统计

> 状态：**✅ 已实施并验证** · 方案写于 2026-09-29 · 实施同日完成 · 作者 02
> 目标项目：`p4-exam-tracker`（Spring Boot 3.5.14 / Java 17）
> 目的：给已有项目加一层「AI 能力」，使其同时满足赴日求职所需的
> **「1 个讲得透的 Java 后端项目」+「1 个 Java+AI 应用项目」**，而不是再新建一个项目。

> **实施结果摘要**（详细的落地说明与实测记录在 `README.md` 第 7.5 节，不在这里重复）：
>
> - 第 11 节那三个待确认问题的答案是**「都做」** —— 模型用 DeepSeek、做多轮对话、前端一起改。
> - 单测 217 → **309**，集成测试 29 → **35**（新增 6 个 AI 接口契约用例）。
> - 另加两层只有真调模型 / 真开浏览器才能验的：`tools/p4-ai-e2e.py`（31 项）、`tools/p4-ai-ui.js`（26 项）。
> - 方案里**没预料到**的三件事（都是实测才发现的）：
>   ① 只要 `spring-ai-starter-model-openai` 在 classpath 上、没配 key，应用**启动就失败**（六个自动配置每个都要 key）⇒ 定下了「AI 层默认关闭」的设计；
>   ② 初设的 20 秒超时**实测真的超时过一次** ⇒ 改成 45 秒，并加了一层错误文案翻译（JDK 的原话 `Request cancelled` 对用户毫无意义）；
>   ③ 前端只实现了 `**加粗**` 和列表两种 Markdown，而模型爱输出表格（会显示成裸竖线）⇒ 在系统提示词里明确禁止。
>
> 下面保留的是**方案原文**，未按实施结果改写 —— 它的价值在于记录「当时为什么这么判断」，
> 事后把判断改写成「果然如此」反而丢掉了信息。

---

## 1. 一句话设计

**把自然语言问题翻译成「对已有 Service 方法的调用」，而不是翻译成 SQL。**

用户问「我这周数学做了多久」→ LLM 输出 `getDailyMinutes(subjectName="数学", from=…, to=…)`
→ 服务端把它落到 `CheckinService.dailyMinutes(userId, from, to)` 上 → 拿到已有 DTO → 交给 LLM 组织成人话。

---

## 2. 为什么不做 Text2SQL（★ 这是本方案最该讲清楚的设计判断）

| 维度 | Text2SQL（让模型生成 SQL） | Function Calling（映射到已有 Service） |
|---|---|---|
| **越权防护** | SQL 里可能漏掉 `user_id` 条件 → **能查到别人的数据**。靠提示词约束是不可靠的 | Service 方法**第一个参数就是 `userId`**，隔离是**代码保证**的，不依赖模型守规矩 |
| **安全边界** | 要防注入、要限制只读、要限制可访问的表 | 模型只能调用白名单里的 5 个方法，没有第六个 |
| **可测性** | 模型输出不确定 → 写不出确定性单测 | 每个工具方法都能写单测；底层已有 **217 个单测 + 29 个集成测试**覆盖 |
| **实现复杂度** | 要喂 schema、要处理方言差异、要纠错重试 | 工具签名就是 Java 方法签名，改一行 `@Tool` 即可 |
| **能力边界** | 复杂 SQL 能表达的查询都能做 | 复杂查询需要新增工具方法（可接受，且天然受控） |

**结论：v1 用 Function Calling。**
Text2SQL 留作 v2 的「高级模式」，且必须挂在**只读从库 + 白名单视图**上，绝不能直连业务表。

> 顺带一个调研结论：Java 侧的 Text2SQL 开源项目很薄
> （`gh search repos "text2sql java stars:>30"` 只搜到 1 个，`agents-flex/agents-flex` 1,068 star）。
> 所以将来真做 Text2SQL，不是「又一个轮子」，有差异化空间。

---

## 3. 依赖与版本（★ 已实测核对，不是猜的）

**必须用 Spring AI 1.x，不能用 2.x。**

| 版本 | 是否可用 | 依据 |
|---|---|---|
| `2.0.1`（最新稳定） | ❌ **不可用** | 其 `spring-ai-starter-model-openai` 依赖 `spring-boot-starter-restclient` / `-webclient`，而这两个 artifact **从 `4.0.0-M1` 才开始存在**（查 `maven-metadata.xml` 确认）⇒ **要求 Spring Boot 4.x**，与本项目的 3.5.14 不兼容 |
| `1.1.8`（最新 1.x） | ✅ **选它** | 只依赖通用的 `spring-boot-starter`；`spring-ai-commons` 字节码 `major=61`（Java 17），与本项目 Java 17 一致 |

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>1.1.8</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

> ⚠️ 本地那份参考项目 `D:/java-workspace/p1-yu-ai-agent`（鱼皮「AI 超级智能体」，2,700 star）
> 用的是 **Spring AI 1.0.0**。**读它的架构和写法，但版本按本项目的 1.1.8 来。**
> OpenAI 兼容端点（DeepSeek 等）的 `base-url` 与模型名待确认后填入配置，本方案不假设它能用。

---

## 4. ★ 关键机制：`userId` 不经过 LLM

这是整个方案的安全基石，**已在 Spring AI 1.1.8 的字节码上验证过链路**：

```
ChatClientRequestSpec.toolContext(Map<String,Object>)     ← 每请求注入
        ↓
ToolCallback.call(String, ToolContext)                     ← 传递
        ↓
MethodToolCallback.validateToolContextSupport(ToolContext) ← 参数解析
        ↓
@Tool 方法接收 ToolContext 参数 → ctx.getContext().get("userId")
```

**模型看到的是「查这周的统计」，看不到也改不了 `userId`。** 它没有把 `userId` 当参数传的机会。

```java
// 示意骨架（不是最终代码）
String answer = chatClient.prompt()
        .system("""
                你是备考助手。今天是 %s。
                只回答与学习数据有关的问题；数据不足时明确说「查不到」，不要编。
                涉及「我」的数据时不要向用户索要任何 ID。
                """.formatted(LocalDate.now()))
        .user(question)
        .tools(examTrackerTools)                                  // 单例 Bean，工具集合固定
        .toolContext(Map.of("userId", principal.id()))             // ★ 服务端注入
        .call()
        .content();
```

```java
@Component
public class ExamTrackerTools {

    private static Long uid(ToolContext ctx) {
        Object v = ctx.getContext().get("userId");
        if (!(v instanceof Long id)) {                 // 缺了就失败，绝不兜底成「查全部」
            throw new IllegalStateException("缺少 userId 上下文");
        }
        return id;
    }

    @Tool(description = "查询整体学习统计：今日任务完成情况、累计时长、连续打卡天数")
    public OverviewResponse getOverview(ToolContext ctx) {
        return statsService.overview(uid(ctx));
    }
}
```

**注意 `uid()` 的失败策略**：拿不到 `userId` 时**抛异常**，不要退化成「不带 userId 的查询」——
那会变成一次越权。这条要写进单测。

---

## 5. 工具清单（v1 **只读**）

全部映射到已有方法，不新增数据访问逻辑。

| 工具名 | 底层方法 | 模型可见参数 | 说明 |
|---|---|---|---|
| `getOverview` | `StatsService.overview(userId)` | 无 | 14 个字段的总览 |
| `getSubjectBoard` | `StatsService.subjectBoard(userId, days)` | `days` | 四科看板，`days` 上限 365 |
| `listSubjects` | `SubjectService.list(userId)` | 无 | 科目清单（也是名称→id 的解析源） |
| `searchTasks` | `TaskService.list(userId, filter, page, size, …)` | `from` `to` `subjectName` `status` `priority` `keyword` `page` `size` | 任务检索，`size` 上限见 `TaskService.maxPageSize()` |
| `getDailyMinutes` | `CheckinService.dailyMinutes(userId, from, to)` | `from` `to` `subjectName` | 打卡按天汇总 |

### ★ v1 明确**不含写操作**

建任务、改状态、打卡这些**不做**。理由：

1. **风险/收益不划算** —— 读错了重问一次就行；写错了要回滚数据。
2. **LLM 写库在面试里是减分项**，除非你能讲清「怎么防误写」。v1 干脆不碰。
3. v2 如果要做，形态必须是：**模型只生成「操作预览」，由用户点确认后才落库** ——
   即写操作永远由人触发，不由模型触发。

---

## 6. 参数怎么给，模型才不乱编

LLM 最容易在三个地方编造参数。对应的处理：

| 风险 | 处理 |
|---|---|
| **`subjectId` 是 Long，模型会瞎编数字** | 工具签名**不暴露 id**，改成 `subjectName`(String)。工具内部用 `SubjectService.list()` 做「名称 → id」解析；**精确匹配失败就返回候选科目名列表**，让模型自己再问用户一次（而不是猜一个 id） |
| **日期模型算不准** | 工具只接收相对语义（`this_week` / `last_7_days` / `today`），**由工具内部换算成 `LocalDate`**；同时把 `today` 写进 system prompt 让模型有参照 |
| **枚举值拼错** | `status` / `priority` 用 `String` 接收，内部 `valueOf` + 失败时**返回合法取值列表**，让模型纠正重试 |

一句话原则：**凡是「模型可能编错、且编错的代价大」的参数，都不要让它直接给值，而是给它「选项」。**

---

## 7. 输出与可观测

```java
public record AiAnswerResponse(
        String answer,            // 给人看的话
        List<String> toolsUsed,   // 这次调了哪些工具（前端可折叠展示）
        boolean degraded          // LLM 不可用时为 true
) {}
```

- **工具调用轨迹必须记日志**。排查「模型为什么这么答」的唯一手段就是看它调了什么、拿到什么。
- **降级策略**：LLM 不可用（超时 / 配额 / 网络）→ 返回 `503` + 明确提示，**不要静默返回空答案**。
  静默返回空是「假绿」的一种，用户在页面上看到的是「查不到数据」，会误判成自己没打卡。
- 超时要显式设置（建议 20s），并且**不要**让 AI 接口拖垮整个应用（独立线程池 / 限流）。

---

## 8. 测试策略（延续现有 5 层，不破坏既有节奏）

| 层 | 测什么 | 是否进 CI |
|---|---|---|
| 单元测试 | `ExamTrackerTools` 每个方法（mock 掉 Service）；**重点测 `uid()` 缺失时抛异常** | ✅ 进（纯 Java，毫秒级） |
| 集成测试 | `ExamTrackerIT` 加一组：真实 MySQL 下直接调 Tools，断言返回的字段与越权防护 | ✅ 进（不需要 API key） |
| LLM 调用本身 | 用一个 `LlmGateway` 接口隔离，测试里换替身 | ❌ **不进 CI** |

**为什么 LLM 调用不进 CI**：需要 API key（要进 secret），且输出不确定 ——
进了 CI 就会变成「时红时绿」，最终没人看。这和现有「surefire 只跑纯 Mockito」的分工思路一致。

---

## 9. 排期（在「日语 N2 是门槛项」的前提下）

| 阶段 | 内容 | 产出 |
|---|---|---|
| 第 1 周 | 依赖接入 + `ChatClient` Bean + **只跑通 1 个工具**（`getOverview`） | 端到端能看到一句人话回答 |
| 第 2 周 | 补齐其余 4 个工具 + 参数映射（科目名解析 / 日期换算）+ 降级与超时 | 5 个工具全通 |
| 第 3 周 | 单测 + 集成测试 + README 加一节「AI 层设计」 | 可讲、可演示 |

**明确不做**（写进 README 的「已知限制」，避免被追问时说不清）：
多轮对话记忆、向量检索（RAG）、写操作、多模型路由、流式输出。

---

## 10. 面试能讲的点（这才是做这个项目的目的）

1. **为什么不做 Text2SQL** —— 越权防护不能依赖提示词，要靠方法签名的第一参数（见 §2）。
2. **`userId` 怎么做到不经过模型** —— `toolContext` 机制 + 拿不到就抛异常（见 §4）。
3. **怎么防止模型编参数** —— 不给值、给选项；精确匹配失败就回候选（见 §6）。
4. **LLM 相关的东西怎么测** —— 接口隔离 + 测试替身；LLM 调用不进 CI 的理由（见 §8）。
5. **为什么 v1 不做写操作** —— 风险/收益比 + 写操作必须由人触发（见 §5）。

---

## 11. 待确认（需要你定，我不替你猜）—— ✅ 已确认

> **已确认（用户答复「都做」）**，以下是当时的问题与最终结论，保留原文：

1. **用哪家的模型？** DeepSeek / OpenAI / 其他 —— 决定 `base-url`、模型名、以及 API key 从哪个环境变量读。
   本方案不假设「OpenAI 兼容端点一定可用」，配好后要实测一次。
   → **答：DeepSeek。** `base-url=https://api.deepseek.com`、`model=deepseek-flash`、
   key 从 `DEEPSEEK_API_KEY` 环境变量读。**function calling 已实测可用**：
   `finish_reason: "tool_calls"`，且一次能返回**多个并行** tool call。
2. **要不要多轮对话？** 需要则要引入 `ChatMemory`，排期 +2~3 天。**我建议 v1 不做。**
   → **答：要做**（我的建议被否了）。实现方式见 README 7.5.4：内存态 + TTL，
   **没有**引入 Redis 或数据库表。
3. **前端要不要一起改？** 现有静态前端（`app.js`）加一个输入框 + 答案区即可；也可以先只出接口，用 Swagger 演示。
   → **答：一起改。** `index.html` +51 行、`style.css` +164 行、`app.js` +147 行，
   含示例问题 chip、工具调用轨迹、`degraded` 提示、多轮「重新开始」。

---

## 附：本方案中所有「已核实」的事实

| 事实 | 核实方式 |
|---|---|
| Spring AI 2.0.1 需要 Boot 4.x | `spring-boot-starter-restclient` 的 `maven-metadata.xml` 最早版本为 `4.0.0-M1` |
| Spring AI 1.1.8 兼容 Java 17 | 下载 jar，读 class 文件 `major=61` |
| `toolContext(Map)` 存在 | `javap` 读 `ChatClient$ChatClientRequestSpec` |
| `@Tool` 方法可收 `ToolContext` | `javap` 读 `ToolCallback.call(String, ToolContext)` 与 `MethodToolCallback.validateToolContextSupport` |
| 现有 Service 方法签名 | 读 `StatsService` / `TaskService` / `SubjectService` / `CheckinService` 源码 |
| 配置写法 | 读 `CorsProperties` / `JwtProperties` 的 `@ConfigurationProperties` 模式 |
