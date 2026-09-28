package com.wpc725562.examtracker.ai;

import com.wpc725562.examtracker.common.PageResult;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 暴露给模型的工具。
 *
 * <p>这个类是整个 AI 层的安全边界，所以这里测的不是「能不能查出数据」，
 * 而是<b>四类容易出错的边界</b>：
 *
 * <ol>
 *   <li><b>userId 取不到时必须抛异常</b>，不能退化成「不带 userId 的查询」——
 *       那是一次越权，而不是一次失败。</li>
 *   <li><b>参数纠错消息要带候选值</b>：模型拼错科目名/枚举时，
 *       异常消息就是它唯一的纠错依据，必须列出合法取值。</li>
 *   <li><b>数据被截断时要如实说明</b>：模型拿不完整的列表下完整的结论，
 *       用户是看不出来的。</li>
 *   <li><b>服务端决定返回多少条</b>，模型没有这个参数可调。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExamTrackerTools")
class ExamTrackerToolsTest {

    private static final Long USER = 7L;

    @Mock
    private StatsService statsService;
    @Mock
    private SubjectService subjectService;
    @Mock
    private TaskService taskService;
    @Mock
    private CheckinService checkinService;

    @InjectMocks
    private ExamTrackerTools tools;

    // ------------------------------------------------------------------ 工具方法 ---

    private static ToolContext ctx(Long userId) {
        return new ToolContext(Map.of(ExamTrackerTools.USER_ID_KEY, userId));
    }

    private static SubjectDtos.SubjectResponse subject(Long id, String name) {
        return new SubjectDtos.SubjectResponse(id, name, "#4F46E5", 420, 0);
    }

    private static TaskDtos.TaskResponse task(long id, String subjectName, String title,
                                             LocalDate planDate, TaskStatus status) {
        return new TaskDtos.TaskResponse(id, 33L, subjectName, title, planDate, 60,
                Priority.MEDIUM, status, null, null, null, null);
    }

    private static CheckinDtos.CheckinResponse checkin(long id, String subjectName,
                                                       LocalDate date, int minutes) {
        return new CheckinDtos.CheckinResponse(id, 33L, subjectName, null, null, date, minutes, null, null);
    }

    private static StatsDtos.OverviewResponse overview(long todayTotal, long todayDone,
                                                       long todayActual, long totalTasks) {
        return new StatsDtos.OverviewResponse(
                LocalDate.of(2026, 9, 29),
                todayTotal, todayDone, todayTotal - todayDone, 0,
                0.5, 120, todayActual,
                7, 10, 23, LocalDate.of(2026, 9, 28),
                totalTasks, 12, 1234,
                LocalDate.of(2027, 3, 14), 166L);
    }

    private static StatsDtos.SubjectBoardResponse board(int periodDays) {
        return new StatsDtos.SubjectBoardResponse(periodDays,
                LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 29),
                815, 1380, 0.59, List.of());
    }

    // ================================================================== 上下文 ===

    @Nested
    @DisplayName("★ userId 上下文：取不到就抛，绝不退化成查全部")
    class UserIdContext {

        @Test
        @DisplayName("ToolContext 为 null → 抛异常")
        void nullContextRejected() {
            assertThatThrownBy(() -> ExamTrackerTools.userId(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ToolContext");
        }

        @Test
        @DisplayName("上下文里没有 userId → 抛异常，且说明不会退化成查全部")
        void missingUserIdRejected() {
            ToolContext empty = new ToolContext(new HashMap<>());

            assertThatThrownBy(() -> ExamTrackerTools.userId(empty))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("userId")
                    .hasMessageContaining("全部数据");
        }

        @Test
        @DisplayName("userId 类型不对（比如是 String）→ 同样抛异常，不做隐式转换")
        void wrongTypeRejected() {
            ToolContext wrong = new ToolContext(Map.of(ExamTrackerTools.USER_ID_KEY, "7"));

            assertThatThrownBy(() -> ExamTrackerTools.userId(wrong))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("userId");
        }

        @Test
        @DisplayName("★ 缺 userId 时**根本不会**去调 Service —— 失败发生在越权之前")
        void serviceIsNotCalledWithoutUserId() {
            ToolContext empty = new ToolContext(new HashMap<>());

            assertThatThrownBy(() -> tools.getOverview(empty))
                    .isInstanceOf(IllegalStateException.class);

            verify(statsService, never()).overview(any());
        }

        @Test
        @DisplayName("每个工具都用上下文里的 userId，而不是别处来的")
        void everyToolUsesContextUserId() {
            when(statsService.overview(USER)).thenReturn(overview(3, 1, 30, 37));
            when(subjectService.list(USER)).thenReturn(List.of(subject(33L, "数学")));

            assertThat(tools.getOverview(ctx(USER)).totalTasks()).isEqualTo(37);
            assertThat(tools.listSubjects(ctx(USER))).hasSize(1);

            verify(statsService).overview(USER);
            verify(subjectService).list(USER);
        }
    }

    // =============================================================== getOverview ===

    @Test
    @DisplayName("getOverview 原样返回统计服务的结果")
    void getOverviewDelegates() {
        when(statsService.overview(USER)).thenReturn(overview(3, 2, 45, 37));

        StatsDtos.OverviewResponse got = tools.getOverview(ctx(USER));

        assertThat(got.todayTotalTasks()).isEqualTo(3);
        assertThat(got.todayDoneTasks()).isEqualTo(2);
        assertThat(got.todayActualMinutes()).isEqualTo(45);
        assertThat(got.daysUntilExam()).isEqualTo(166L);
    }

    // =========================================================== getSubjectBoard ===

    @Nested
    @DisplayName("getSubjectBoard：窗口天数会被夹到 1~365")
    class SubjectBoard {

        @Test
        @DisplayName("不传天数时交给 Service 用自己的默认值（传 null）")
        void nullDaysPassedThrough() {
            when(statsService.subjectBoard(USER, null)).thenReturn(board(7));

            assertThat(tools.getSubjectBoard(null, ctx(USER)).periodDays()).isEqualTo(7);

            verify(statsService).subjectBoard(USER, null);
        }

        @Test
        @DisplayName("正常值原样透传")
        void normalDaysPassedThrough() {
            when(statsService.subjectBoard(USER, 30)).thenReturn(board(30));

            tools.getSubjectBoard(30, ctx(USER));

            verify(statsService).subjectBoard(USER, 30);
        }

        @Test
        @DisplayName("★ 0 / 负数被夹到 1，超大值被夹到 365 —— 而不是让 Service 抛参数异常")
        void outOfRangeClamped() {
            when(statsService.subjectBoard(USER, 1)).thenReturn(board(1));
            when(statsService.subjectBoard(USER, 365)).thenReturn(board(365));

            tools.getSubjectBoard(0, ctx(USER));
            tools.getSubjectBoard(-5, ctx(USER));
            verify(statsService, times(2)).subjectBoard(USER, 1);

            tools.getSubjectBoard(100_000, ctx(USER));
            verify(statsService).subjectBoard(USER, 365);
        }
    }

    // ============================================================== listSubjects ===

    @Test
    @DisplayName("listSubjects 返回全部科目")
    void listSubjectsDelegates() {
        when(subjectService.list(USER))
                .thenReturn(List.of(subject(33L, "数学"), subject(34L, "英语")));

        assertThat(tools.listSubjects(ctx(USER)))
                .extracting(SubjectDtos.SubjectResponse::name)
                .containsExactly("数学", "英语");
    }

    // =============================================================== searchTasks ===

    @Nested
    @DisplayName("searchTasks：参数防编造")
    class SearchTasks {

        @Test
        @DisplayName("★ 科目名对不上时，异常消息里必须带**候选列表**")
        void unknownSubjectListsCandidates() {
            when(subjectService.list(USER))
                    .thenReturn(List.of(subject(33L, "数学"), subject(34L, "英语"), subject(35L, "计算机")));

            assertThatThrownBy(() -> tools.searchTasks("物理", null, null, null, null, null, null, ctx(USER)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("物理")
                    .hasMessageContaining("数学")
                    .hasMessageContaining("英语")
                    .hasMessageContaining("计算机");

            verify(taskService, never()).list(any(), any(), anyInt(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("★ 一个科目都没有时，明确告诉模型「先去建科目」，而不是让它猜")
        void noSubjectsAtAll() {
            when(subjectService.list(USER)).thenReturn(List.of());

            assertThatThrownBy(() -> tools.searchTasks("数学", null, null, null, null, null, null, ctx(USER)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("还没有任何科目");
        }

        @Test
        @DisplayName("科目名精确匹配")
        void exactSubjectMatch() {
            when(subjectService.list(USER)).thenReturn(List.of(subject(33L, "数学")));
            when(taskService.list(eq(USER), any(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenReturn(PageResult.ofAll(List.of()));

            tools.searchTasks("数学", null, null, null, null, null, null, ctx(USER));

            ArgumentCaptor<TaskFilter> filter = ArgumentCaptor.forClass(TaskFilter.class);
            verify(taskService).list(eq(USER), filter.capture(), anyInt(), anyInt(), anyString(), anyString());
            assertThat(filter.getValue().subjectId()).isEqualTo(33L);
        }

        @Test
        @DisplayName("忽略大小写与首尾空白（模型常带空格）")
        void subjectMatchIgnoresCaseAndSpace() {
            when(subjectService.list(USER)).thenReturn(List.of(subject(34L, "English")));
            when(taskService.list(eq(USER), any(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenReturn(PageResult.ofAll(List.of()));

            tools.searchTasks("  english ", null, null, null, null, null, null, ctx(USER));

            ArgumentCaptor<TaskFilter> filter = ArgumentCaptor.forClass(TaskFilter.class);
            verify(taskService).list(eq(USER), filter.capture(), anyInt(), anyInt(), anyString(), anyString());
            assertThat(filter.getValue().subjectId()).isEqualTo(34L);
        }

        @Test
        @DisplayName("唯一的包含匹配也算命中（模型说「数学课」而科目叫「数学」）")
        void uniquePartialMatch() {
            when(subjectService.list(USER))
                    .thenReturn(List.of(subject(33L, "数学"), subject(34L, "英语")));
            when(taskService.list(eq(USER), any(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenReturn(PageResult.ofAll(List.of()));

            tools.searchTasks("数学课", null, null, null, null, null, null, ctx(USER));

            ArgumentCaptor<TaskFilter> filter = ArgumentCaptor.forClass(TaskFilter.class);
            verify(taskService).list(eq(USER), filter.capture(), anyInt(), anyInt(), anyString(), anyString());
            assertThat(filter.getValue().subjectId()).isEqualTo(33L);
        }

        @Test
        @DisplayName("★ 包含匹配到多个时不猜，照样报错给候选")
        void ambiguousPartialMatchRejected() {
            when(subjectService.list(USER))
                    .thenReturn(List.of(subject(33L, "高等数学"), subject(34L, "高等物理")));

            assertThatThrownBy(() -> tools.searchTasks("高等", null, null, null, null, null, null, ctx(USER)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("高等数学")
                    .hasMessageContaining("高等物理");
        }

        @Test
        @DisplayName("★ 状态拼错 → 异常里列出合法取值 + 中文含义")
        void badStatusListsLegalValues() {
            when(subjectService.list(USER)).thenReturn(List.of(subject(33L, "数学")));

            assertThatThrownBy(() -> tools.searchTasks("数学", "FINISHED", null, null, null, null, null, ctx(USER)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("FINISHED")
                    .hasMessageContaining("TODO")
                    .hasMessageContaining("DONE")
                    .hasMessageContaining("SKIPPED")
                    .hasMessageContaining("待办");
        }

        @Test
        @DisplayName("★ 优先级拼错 → 同样列出合法取值")
        void badPriorityListsLegalValues() {
            when(subjectService.list(USER)).thenReturn(List.of(subject(33L, "数学")));

            assertThatThrownBy(() -> tools.searchTasks("数学", null, "URGENT", null, null, null, null, ctx(USER)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("URGENT")
                    .hasMessageContaining("HIGH")
                    .hasMessageContaining("MEDIUM")
                    .hasMessageContaining("LOW");
        }

        @Test
        @DisplayName("状态/优先级大小写不敏感")
        void enumsAreCaseInsensitive() {
            when(subjectService.list(USER)).thenReturn(List.of(subject(33L, "数学")));
            when(taskService.list(eq(USER), any(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenReturn(PageResult.ofAll(List.of()));

            tools.searchTasks("数学", "done", "high", null, null, null, null, ctx(USER));

            ArgumentCaptor<TaskFilter> filter = ArgumentCaptor.forClass(TaskFilter.class);
            verify(taskService).list(eq(USER), filter.capture(), anyInt(), anyInt(), anyString(), anyString());
            assertThat(filter.getValue().status()).isEqualTo(TaskStatus.DONE);
            assertThat(filter.getValue().priority()).isEqualTo(Priority.HIGH);
        }

        @Test
        @DisplayName("空白关键词归一成 null（不然会 LIKE '%%' 白扫一遍）")
        void blankKeywordBecomesNull() {
            when(taskService.list(eq(USER), any(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenReturn(PageResult.ofAll(List.of()));

            tools.searchTasks(null, null, null, "   ", null, null, null, ctx(USER));

            ArgumentCaptor<TaskFilter> filter = ArgumentCaptor.forClass(TaskFilter.class);
            verify(taskService).list(eq(USER), filter.capture(), anyInt(), anyInt(), anyString(), anyString());
            assertThat(filter.getValue().keyword()).isNull();
        }

        @Test
        @DisplayName("★ 命中超过上限时给 note，如实说明被截断")
        void truncatedResultCarriesNote() {
            // 服务端固定取 10+1 条来判断「还有没有更多」
            when(taskService.list(eq(USER), any(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenReturn(new PageResult<>(
                            List.of(task(1, "数学", "a", LocalDate.of(2026, 9, 29), TaskStatus.TODO)),
                            1, 11, 37, 4));

            AiDtos.TaskSearchResult result =
                    tools.searchTasks(null, null, null, null, null, null, null, ctx(USER));

            assertThat(result.matched()).isEqualTo(37);
            assertThat(result.tasks()).hasSize(1);
            assertThat(result.note())
                    .as("截断必须告知模型，否则它会拿不完整的列表下完整结论")
                    .contains("37")
                    .contains("10");
        }

        @Test
        @DisplayName("没超过上限时 note 为 null（JSON 里不会出现这个字段）")
        void notTruncatedHasNoNote() {
            when(taskService.list(eq(USER), any(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenReturn(PageResult.ofAll(List.of(
                            task(1, "数学", "a", LocalDate.of(2026, 9, 29), TaskStatus.TODO))));

            AiDtos.TaskSearchResult result =
                    tools.searchTasks(null, null, null, null, null, null, null, ctx(USER));

            assertThat(result.note()).isNull();
        }

        @Test
        @DisplayName("★ 条数上限由服务端定死，模型没有 size 参数可传")
        void modelCannotChoosePageSize() {
            when(taskService.list(eq(USER), any(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenReturn(PageResult.ofAll(List.of()));

            tools.searchTasks(null, null, null, null, null, null, null, ctx(USER));

            // 第 4 个参数是 size：固定为 11（10 条上限 + 1 条用于判断是否还有更多）
            verify(taskService).list(eq(USER), any(), eq(1), eq(11), anyString(), anyString());
        }

        @Test
        @DisplayName("时间范围默认是最近 7 天（含今天）")
        void defaultRangeIsLast7Days() {
            when(taskService.list(eq(USER), any(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenReturn(PageResult.ofAll(List.of()));

            tools.searchTasks(null, null, null, null, null, null, null, ctx(USER));

            ArgumentCaptor<TaskFilter> filter = ArgumentCaptor.forClass(TaskFilter.class);
            verify(taskService).list(eq(USER), filter.capture(), anyInt(), anyInt(), anyString(), anyString());

            LocalDate today = LocalDate.now();
            assertThat(filter.getValue().to()).isEqualTo(today);
            assertThat(filter.getValue().from()).isEqualTo(today.minusDays(6));
        }

        @Test
        @DisplayName("显式日期优先于 range")
        void explicitDatesWin() {
            when(taskService.list(eq(USER), any(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenReturn(PageResult.ofAll(List.of()));

            tools.searchTasks(null, null, null, null, "today", "2026-09-01", "2026-09-10", ctx(USER));

            ArgumentCaptor<TaskFilter> filter = ArgumentCaptor.forClass(TaskFilter.class);
            verify(taskService).list(eq(USER), filter.capture(), anyInt(), anyInt(), anyString(), anyString());
            assertThat(filter.getValue().from()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(filter.getValue().to()).isEqualTo(LocalDate.of(2026, 9, 10));
        }

        @Test
        @DisplayName("返回的是精简结构：不带 id 之外的内部字段，且枚举转成字符串")
        void briefShapeIsNarrow() {
            when(taskService.list(eq(USER), any(), anyInt(), anyInt(), anyString(), anyString()))
                    .thenReturn(PageResult.ofAll(List.of(
                            task(99, "数学", "做一套真题", LocalDate.of(2026, 9, 28), TaskStatus.DONE))));

            AiDtos.TaskSearchResult result =
                    tools.searchTasks(null, null, null, null, null, null, null, ctx(USER));

            AiDtos.TaskBrief brief = result.tasks().get(0);
            assertThat(brief.id()).isEqualTo(99L);
            assertThat(brief.subjectName()).isEqualTo("数学");
            assertThat(brief.title()).isEqualTo("做一套真题");
            assertThat(brief.planDate()).isEqualTo(LocalDate.of(2026, 9, 28));
            assertThat(brief.priority()).isEqualTo("MEDIUM");
            assertThat(brief.status()).isEqualTo("DONE");
        }
    }

    // ============================================================ getDailyMinutes ===

    @Nested
    @DisplayName("getDailyMinutes")
    class DailyMinutes {

        @Test
        @DisplayName("不指定科目时走 SQL 聚合（数字精确，不受分页影响）")
        void allSubjectsUsesAggregate() {
            LocalDate today = LocalDate.now();
            when(checkinService.dailyMinutes(eq(USER), eq(today.minusDays(6)), eq(today)))
                    .thenReturn(List.of(
                            new CheckinDtos.DailyMinutes(today.minusDays(2), 90L),
                            new CheckinDtos.DailyMinutes(today, 45L)));

            AiDtos.DailyMinutesResult result =
                    tools.getDailyMinutes(null, "last_7_days", null, null, ctx(USER));

            assertThat(result.subject()).isEqualTo("全部科目");
            assertThat(result.totalMinutes()).isEqualTo(135L);
            assertThat(result.days()).hasSize(2);
            assertThat(result.note()).isNull();

            // 这条路径**不应该**去查分页明细
            verify(checkinService, never()).list(any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("★ 指定科目时按天聚合、按日期升序（模型更容易讲成「逐日趋势」）")
        void perSubjectAggregatesByDate() {
            LocalDate today = LocalDate.now();
            when(subjectService.list(USER)).thenReturn(List.of(subject(33L, "数学")));
            when(checkinService.list(eq(USER), eq(today.minusDays(6)), eq(today), eq(33L), eq(1), anyInt()))
                    .thenReturn(PageResult.ofAll(List.of(
                            // 故意给倒序：聚合后必须重新排序
                            checkin(3, "数学", today, 30),
                            checkin(2, "数学", today.minusDays(1), 20),
                            checkin(1, "数学", today, 10))));

            AiDtos.DailyMinutesResult result =
                    tools.getDailyMinutes("数学", "last_7_days", null, null, ctx(USER));

            assertThat(result.subject()).isEqualTo("数学");
            assertThat(result.totalMinutes()).as("30 + 20 + 10").isEqualTo(60L);
            assertThat(result.days()).hasSize(2);
            assertThat(result.days().get(0).date())
                    .as("升序：较早的日期在前")
                    .isEqualTo(today.minusDays(1));
            assertThat(result.days().get(0).minutes()).isEqualTo(20L);
            assertThat(result.days().get(1).date()).isEqualTo(today);
            assertThat(result.days().get(1).minutes())
                    .as("同一天的多次打卡要合并")
                    .isEqualTo(40L);
        }

        @Test
        @DisplayName("★ 明细超过扫描上限时给 note，并说明合计**偏小**（这是最容易骗过用户的坑）")
        void truncatedCheckinsWarnAboutUnderCount() {
            LocalDate today = LocalDate.now();
            when(subjectService.list(USER)).thenReturn(List.of(subject(33L, "数学")));
            when(checkinService.list(eq(USER), any(), any(), eq(33L), eq(1), anyInt()))
                    .thenReturn(new PageResult<>(
                            List.of(checkin(1, "数学", today, 30)), 1, 200, 500, 3));

            AiDtos.DailyMinutesResult result =
                    tools.getDailyMinutes("数学", "last_7_days", null, null, ctx(USER));

            assertThat(result.note())
                    .as("合计是偏小的，必须说清楚，否则模型会当成准确值报给用户")
                    .contains("偏小")
                    .contains("200");
        }

        @Test
        @DisplayName("科目名不对 → 候选列表")
        void unknownSubjectListsCandidates() {
            when(subjectService.list(USER)).thenReturn(List.of(subject(33L, "数学"), subject(34L, "英语")));

            assertThatThrownBy(() -> tools.getDailyMinutes("物理", null, null, null, ctx(USER)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("物理")
                    .hasMessageContaining("数学");
        }

        @Test
        @DisplayName("打卡记录为 null 分钟数时按 0 处理，不 NPE")
        void nullMinutesTreatedAsZero() {
            LocalDate today = LocalDate.now();
            when(subjectService.list(USER)).thenReturn(List.of(subject(33L, "数学")));
            when(checkinService.list(eq(USER), any(), any(), eq(33L), eq(1), anyInt()))
                    .thenReturn(PageResult.ofAll(List.of(
                            new CheckinDtos.CheckinResponse(1L, 33L, "数学", null, null, today, null, null, null))));

            AiDtos.DailyMinutesResult result =
                    tools.getDailyMinutes("数学", null, null, null, ctx(USER));

            assertThat(result.totalMinutes()).isZero();
        }

        @Test
        @DisplayName("区间内没有任何打卡 → 空列表 + 0 分钟（而不是报错）")
        void emptyRangeIsFine() {
            LocalDate today = LocalDate.now();
            when(checkinService.dailyMinutes(eq(USER), any(), any())).thenReturn(List.of());

            AiDtos.DailyMinutesResult result =
                    tools.getDailyMinutes(null, "today", null, null, ctx(USER));

            assertThat(result.totalMinutes()).isZero();
            assertThat(result.days()).isEmpty();
            assertThat(result.from()).isEqualTo(today);
            assertThat(result.to()).isEqualTo(today);
        }

        @Test
        @DisplayName("非法 range 会抛异常（候选值机制对每个工具都生效）")
        void badRangeRejected() {
            assertThatThrownBy(() -> tools.getDailyMinutes(null, "yesterday", null, null, ctx(USER)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("this_week");
        }
    }

    // ============================================================== 工具集完整性 ===

    @Test
    @DisplayName("★ 暴露给模型的工具集合是固定的 5 个，且全部是只读的（没有写操作）")
    void toolSetIsFixedAndReadOnly() {
        var names = java.util.Arrays.stream(
                        org.springframework.ai.support.ToolCallbacks.from(tools))
                .map(cb -> cb.getToolDefinition().name())
                .sorted()
                .toList();

        assertThat(names).containsExactly(
                "getDailyMinutes", "getOverview", "getSubjectBoard", "listSubjects", "searchTasks");

        assertThat(names)
                .as("v1 明确不含任何写操作 —— 建任务/改状态/打卡都不在工具集里")
                .noneMatch(n -> n.toLowerCase().contains("create")
                        || n.toLowerCase().contains("update")
                        || n.toLowerCase().contains("delete")
                        || n.toLowerCase().contains("checkin")
                        || n.toLowerCase().contains("status"));
    }
}
