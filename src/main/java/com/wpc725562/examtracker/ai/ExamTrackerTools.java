package com.wpc725562.examtracker.ai;

import com.wpc725562.examtracker.domain.Priority;
import com.wpc725562.examtracker.domain.TaskStatus;
import com.wpc725562.examtracker.dto.CheckinDtos;
import com.wpc725562.examtracker.dto.StatsDtos;
import com.wpc725562.examtracker.dto.SubjectDtos;
import com.wpc725562.examtracker.dto.TaskDtos;
import com.wpc725562.examtracker.dto.TaskFilter;
import com.wpc725562.examtracker.service.CheckinService;
import com.wpc725562.examtracker.service.StatsService;
import com.wpc725562.examtracker.service.SubjectService;
import com.wpc725562.examtracker.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 暴露给模型的工具集合。
 *
 * <p><b>这是整个 AI 层唯一和模型接触的地方。</b>五个方法全部是<b>只读</b>的，
 * 并且每一个都直接落在已有的 Service 方法上 —— 没有一行新的数据访问代码。
 *
 * <h3>三条设计原则</h3>
 *
 * <p><b>1. {@code userId} 不经过模型。</b>它由 {@link ToolContext} 从服务端注入，
 * 模型看不到也改不了。这是越权防护的<b>结构性</b>保证：模型没有「把 userId 当参数传」的机会，
 * 而不是靠提示词叮嘱它「不要查别人的数据」。取不到 userId 时<b>抛异常</b>，
 * 绝不退化成「不带 userId 的查询」—— 那正是一次越权。
 *
 * <p><b>2. 凡是模型可能编错、且编错的代价大的参数，都不给它「值」，只给它「选项」。</b>
 * 科目用名字而不是 id（它编不出一个数字 id 对应哪个科目）；
 * 时间用 {@code this_week} 这种相对语义而不是日期（它没有日历）；
 * 枚举用字符串接收后自己解析（拼错了能把合法取值列给它）。
 * 失败时抛出的异常消息就是给模型的<b>纠错提示</b> ——
 * Spring AI 会把异常消息作为工具结果回传，模型据此重试或转而问用户。
 *
 * <p><b>3. 返回给模型的数据比 REST 接口更窄。</b>不带 id、创建时间这些它用不到的字段：
 * 既省 token，也减少它拿这些数字去编参数的机会。
 *
 * <p><b>为什么不让模型决定返回多少条：</b>它无法判断 token 预算和数据库压力。
 * 条数上限由服务端定死，超了就在返回值里用 {@code note} 如实说明「共 N 条，只给了前 M 条」——
 * 让模型知道数据被截断过，而不是拿着不完整的列表下一个完整的结论。
 */
@Component
public class ExamTrackerTools {

    private static final Logger log = LoggerFactory.getLogger(ExamTrackerTools.class);

    /** {@link ToolContext} 里放 userId 的键。 */
    public static final String USER_ID_KEY = "userId";

    /** 一次返回给模型的任务条数上限。见类注释最后一段。 */
    private static final int TASK_LIMIT = 10;

    /** 按科目查打卡时的扫描上限，与 {@code CheckinService} 的单页上限一致。 */
    private static final int CHECKIN_SCAN_LIMIT = 200;

    private static final int BOARD_MIN_DAYS = 1;
    private static final int BOARD_MAX_DAYS = 365;
    private static final int BOARD_DEFAULT_DAYS = 7;

    private final StatsService statsService;
    private final SubjectService subjectService;
    private final TaskService taskService;
    private final CheckinService checkinService;

    public ExamTrackerTools(StatsService statsService,
                            SubjectService subjectService,
                            TaskService taskService,
                            CheckinService checkinService) {
        this.statsService = statsService;
        this.subjectService = subjectService;
        this.taskService = taskService;
        this.checkinService = checkinService;
    }

    // ------------------------------------------------------------------
    //  工具
    // ------------------------------------------------------------------

    @Tool(description = """
            查询当前登录用户的学习总览。返回：今日任务总数/已完成/待办/跳过、
            今日完成率、今日计划与实际投入分钟数、当前连续打卡天数、历史最长连续天数、
            累计打卡天数、最近一次打卡日期、累计任务数与累计完成数、累计投入分钟数、
            考试日期与距考试天数。
            回答「我今天怎么样」「我整体进度如何」「还有多少天考试」这类问题时用这个。
            不需要任何参数。""")
    public StatsDtos.OverviewResponse getOverview(ToolContext ctx) {
        Long userId = userId(ctx);
        StatsDtos.OverviewResponse result = statsService.overview(userId);
        log.info("[AI工具] getOverview userId={} -> 今日任务 {} 项、累计投入 {} 分钟",
                userId, result.todayTotalTasks(), result.totalActualMinutes());
        return result;
    }

    @Tool(description = """
            查询当前登录用户各科目的进度看板。每一科返回：任务总数/已完成/未完成/完成率（全量）、
            统计窗口内的实际投入分钟数与应投入分钟数、窗口达成率、最近一次打卡日期。
            回答「各科进度怎么样」「哪科拖后腿了」这类问题时用这个。
            periodDays 是统计窗口天数，1~365，不填默认 7（即最近一周）。""")
    public StatsDtos.SubjectBoardResponse getSubjectBoard(
            @ToolParam(description = "统计窗口天数，取值 1~365，不填默认 7", required = false) Integer periodDays,
            ToolContext ctx) {

        Long userId = userId(ctx);
        Integer days = periodDays == null
                ? null
                : Math.min(BOARD_MAX_DAYS, Math.max(BOARD_MIN_DAYS, periodDays));

        StatsDtos.SubjectBoardResponse result = statsService.subjectBoard(userId, days);
        log.info("[AI工具] getSubjectBoard userId={} periodDays={} -> {} 个科目、窗口 {} ~ {}",
                userId, result.periodDays(), result.subjects().size(), result.from(), result.to());
        return result;
    }

    @Tool(description = """
            列出当前登录用户的全部科目（名称、颜色、每周目标分钟数）。
            回答「我有哪些科目」时用这个；也可以用它来确认科目名怎么写，
            免得在其它工具里把科目名写错。不需要任何参数。""")
    public List<SubjectDtos.SubjectResponse> listSubjects(ToolContext ctx) {
        Long userId = userId(ctx);
        List<SubjectDtos.SubjectResponse> result = subjectService.list(userId);
        log.info("[AI工具] listSubjects userId={} -> {}", userId, result.stream()
                .map(SubjectDtos.SubjectResponse::name).toList());
        return result;
    }

    @Tool(description = """
            检索当前登录用户的任务。所有参数都是可选的，不传就是不过滤。
            返回符合条件的总条数、以及最多 10 条任务明细（科目名、标题、计划日期、计划分钟数、优先级、状态）。
            回答「这周还有哪些没做完」「数学有哪些任务」这类问题时用这个。
            时间范围作用在任务的**计划日期**上。""")
    public AiDtos.TaskSearchResult searchTasks(
            @ToolParam(description = "科目名，例如 数学。不填表示所有科目", required = false) String subjectName,
            @ToolParam(description = "任务状态，取值 TODO / DONE / SKIPPED", required = false) String status,
            @ToolParam(description = "优先级，取值 HIGH / MEDIUM / LOW", required = false) String priority,
            @ToolParam(description = "标题关键词，模糊匹配", required = false) String keyword,
            @ToolParam(description = "相对时间范围，取值 today / this_week / last_7_days / last_30_days / this_month",
                    required = false) String range,
            @ToolParam(description = "起始日期，YYYY-MM-DD。与 range 二选一，两个都给时以显式日期为准",
                    required = false) String from,
            @ToolParam(description = "结束日期，YYYY-MM-DD（含当天）", required = false) String to,
            ToolContext ctx) {

        Long userId = userId(ctx);
        DateRanges.Span span = DateRanges.resolve(range, from, to, LocalDate.now());

        Long subjectId = subjectName == null || subjectName.isBlank()
                ? null
                : requireSubject(userId, subjectName).id();

        TaskFilter filter = new TaskFilter(
                span.from(), span.to(), subjectId,
                resolveStatus(status), resolvePriority(priority),
                keyword == null || keyword.isBlank() ? null : keyword.trim());

        // 这里多要 1 条，用来判断「是不是还有更多」—— 否则 note 说不准
        var page = taskService.list(userId, filter, 1, TASK_LIMIT + 1,
                "planDate", "desc");

        List<AiDtos.TaskBrief> briefs = page.items().stream()
                .limit(TASK_LIMIT)
                .map(ExamTrackerTools::toBrief)
                .toList();

        String note = page.total() > TASK_LIMIT
                ? "共 " + page.total() + " 条符合条件，这里只返回了前 " + TASK_LIMIT + " 条。"
                  + "需要看更多请缩小时间范围或加上科目/状态筛选。"
                : null;

        log.info("[AI工具] searchTasks userId={} 条件[科目={} 状态={} 优先级={} 关键词={} 区间={}~{}] -> 命中 {} 条，返回 {} 条",
                userId, subjectName, status, priority, keyword, span.from(), span.to(),
                page.total(), briefs.size());

        return new AiDtos.TaskSearchResult(page.total(), briefs, note);
    }

    @Tool(description = """
            查询当前登录用户在某段时间里每天实际投入了多少分钟（来自打卡记录）。
            可以指定科目，不指定就是全部科目合计。
            回答「我这周数学做了多久」「最近一个月我每天学了多少」这类问题时用这个。
            返回区间总分钟数、逐日明细、以及数据是否被截断的说明。""")
    public AiDtos.DailyMinutesResult getDailyMinutes(
            @ToolParam(description = "科目名，例如 数学。不填表示全部科目合计", required = false) String subjectName,
            @ToolParam(description = "相对时间范围，取值 today / this_week / last_7_days / last_30_days / this_month",
                    required = false) String range,
            @ToolParam(description = "起始日期，YYYY-MM-DD。与 range 二选一，两个都给时以显式日期为准",
                    required = false) String from,
            @ToolParam(description = "结束日期，YYYY-MM-DD（含当天）", required = false) String to,
            ToolContext ctx) {

        Long userId = userId(ctx);
        DateRanges.Span span = DateRanges.resolve(range, from, to, LocalDate.now());

        if (subjectName == null || subjectName.isBlank()) {
            // 不指定科目时走 SQL 聚合，数字是精确的（不受分页影响）
            List<CheckinDtos.DailyMinutes> rows = checkinService.dailyMinutes(userId, span.from(), span.to());
            List<AiDtos.DayMinutes> days = rows.stream()
                    .map(r -> new AiDtos.DayMinutes(r.date(), r.minutes()))
                    .toList();
            long total = rows.stream().mapToLong(CheckinDtos.DailyMinutes::minutes).sum();

            log.info("[AI工具] getDailyMinutes userId={} 全部科目 {} ~ {} -> 合计 {} 分钟、{} 天有记录",
                    userId, span.from(), span.to(), total, days.size());
            return new AiDtos.DailyMinutesResult("全部科目", span.from(), span.to(), total, days, null);
        }

        SubjectDtos.SubjectResponse subject = requireSubject(userId, subjectName);

        var page = checkinService.list(userId, span.from(), span.to(), subject.id(), 1, CHECKIN_SCAN_LIMIT);

        Map<LocalDate, Long> byDate = new LinkedHashMap<>();
        page.items().forEach(c -> byDate.merge(c.checkinDate(),
                c.actualMinutes() == null ? 0L : c.actualMinutes().longValue(), Long::sum));

        List<AiDtos.DayMinutes> days = byDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new AiDtos.DayMinutes(e.getKey(), e.getValue()))
                .toList();
        long total = days.stream().mapToLong(AiDtos.DayMinutes::minutes).sum();

        // 明细是从分页结果聚合出来的，条数超上限时合计会偏小 —— 必须如实告诉模型，
        // 否则它会拿一个偏小的数字当结论，而用户完全看不出来。
        String note = page.total() > CHECKIN_SCAN_LIMIT
                ? "注意：该区间的打卡明细超过 " + CHECKIN_SCAN_LIMIT + " 条，本结果只聚合了前 "
                  + CHECKIN_SCAN_LIMIT + " 条，合计分钟数**偏小**。请缩小时间范围后重查。"
                : null;

        log.info("[AI工具] getDailyMinutes userId={} 科目={} {} ~ {} -> 合计 {} 分钟、{} 天有记录{}",
                userId, subject.name(), span.from(), span.to(), total, days.size(),
                note == null ? "" : "（结果被截断）");

        return new AiDtos.DailyMinutesResult(subject.name(), span.from(), span.to(), total, days, note);
    }

    // ------------------------------------------------------------------
    //  上下文
    // ------------------------------------------------------------------

    /**
     * 从 {@link ToolContext} 取出当前用户 id。
     *
     * <p><b>取不到就抛异常，这是刻意的。</b>任何「兜底」写法 ——
     * 返回 null、返回 0、退化成不带 userId 的查询 —— 都会把一次正常的失败
     * 变成一次<b>越权</b>：不带 userId 的查询会读到全部用户的数据。
     * 宁可这次问答失败，也不能让它答出别人的数据。
     *
     * @throws IllegalStateException 上下文缺失或类型不对
     */
    public static Long userId(ToolContext ctx) {
        if (ctx == null) {
            throw new IllegalStateException("缺少 ToolContext：工具被以非预期的方式调用了");
        }
        Object value = ctx.getContext().get(USER_ID_KEY);
        if (!(value instanceof Long id)) {
            throw new IllegalStateException(
                    "缺少 userId 上下文，拒绝执行查询（不会退化成查询全部数据）");
        }
        return id;
    }

    // ------------------------------------------------------------------
    //  参数解析
    // ------------------------------------------------------------------

    /**
     * 科目名 → 科目。先精确匹配，再忽略大小写，最后唯一包含匹配；都不中就抛异常并把
     * <b>候选列表</b>给模型。
     *
     * <p>为什么不做「猜一个最像的」：猜错的表现是「答的是另一个科目的数据」，
     * 而用户没法从答案里看出来 —— 这是最糟的一类错误。
     * 让模型拿到候选后重新问用户一次，代价只是多一轮对话。
     */
    private SubjectDtos.SubjectResponse requireSubject(Long userId, String subjectName) {
        List<SubjectDtos.SubjectResponse> all = subjectService.list(userId);
        if (all.isEmpty()) {
            throw new IllegalArgumentException(
                    "当前账号还没有任何科目。请先在页面上创建科目，或问用户想追踪哪些科目。");
        }

        String wanted = subjectName.trim();

        for (SubjectDtos.SubjectResponse s : all) {
            if (s.name().equals(wanted)) {
                return s;
            }
        }
        for (SubjectDtos.SubjectResponse s : all) {
            if (s.name().equalsIgnoreCase(wanted)) {
                return s;
            }
        }

        List<SubjectDtos.SubjectResponse> partial = all.stream()
                .filter(s -> s.name().contains(wanted) || wanted.contains(s.name()))
                .toList();
        if (partial.size() == 1) {
            return partial.get(0);
        }

        throw new IllegalArgumentException("找不到科目「" + subjectName + "」。当前可选科目："
                + all.stream().map(SubjectDtos.SubjectResponse::name).collect(Collectors.joining("、"))
                + "。请从中选一个，或先问用户指的是哪一科。");
    }

    /** 枚举拼错时把合法取值连同中文含义一起回给模型。 */
    private static TaskStatus resolveStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        for (TaskStatus s : TaskStatus.values()) {
            if (s.name().equals(key)) {
                return s;
            }
        }
        throw new IllegalArgumentException("任务状态「" + raw + "」不合法，只能是："
                + describe(TaskStatus.values(), Map.of(
                        "TODO", "待办", "DONE", "已完成", "SKIPPED", "已跳过")));
    }

    private static Priority resolvePriority(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        for (Priority p : Priority.values()) {
            if (p.name().equals(key)) {
                return p;
            }
        }
        throw new IllegalArgumentException("优先级「" + raw + "」不合法，只能是："
                + describe(Priority.values(), Map.of("HIGH", "高", "MEDIUM", "中", "LOW", "低")));
    }

    private static <E extends Enum<E>> String describe(E[] values, Map<String, String> labels) {
        List<String> parts = new ArrayList<>();
        for (E v : values) {
            String label = labels.get(v.name());
            parts.add(label == null ? v.name() : v.name() + "(" + label + ")");
        }
        return String.join(" / ", parts);
    }

    private static AiDtos.TaskBrief toBrief(TaskDtos.TaskResponse t) {
        return new AiDtos.TaskBrief(
                t.id(), t.subjectName(), t.title(), t.planDate(), t.planMinutes(),
                t.priority() == null ? null : t.priority().name(),
                t.status() == null ? null : t.status().name());
    }
}
