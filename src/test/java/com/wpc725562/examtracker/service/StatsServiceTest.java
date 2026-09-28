package com.wpc725562.examtracker.service;

import com.wpc725562.examtracker.common.BusinessException;
import com.wpc725562.examtracker.common.ErrorCode;
import com.wpc725562.examtracker.domain.Subject;
import com.wpc725562.examtracker.domain.TaskStatus;
import com.wpc725562.examtracker.domain.User;
import com.wpc725562.examtracker.dto.StatsDtos;
import com.wpc725562.examtracker.dto.SubjectDtos;
import com.wpc725562.examtracker.repository.CheckinRepository;
import com.wpc725562.examtracker.repository.SubjectRepository;
import com.wpc725562.examtracker.repository.TaskRepository;
import com.wpc725562.examtracker.repository.UserRepository;
import com.wpc725562.examtracker.repository.projection.SubjectCount;
import com.wpc725562.examtracker.repository.projection.SubjectLastCheckin;
import com.wpc725562.examtracker.repository.projection.SubjectMinutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统计服务。
 *
 * <p>这里最值得钉住的两条：
 * <ol>
 *   <li><b>分母为 0 时完成率是 0.0，不是 1.0 也不是 NaN</b> ——
 *       「今天一个任务都没安排」是正常状态，返回 1.0 会显示成「全部完成」，是错的；</li>
 *   <li><b>周目标要按窗口折算</b> —— 看板是「最近 N 天」的视图，
 *       拿周目标直接当窗口目标比，窗口越短越显得没达标。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatsService")
class StatsServiceTest {

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private CheckinRepository checkinRepository;
    @Mock
    private SubjectRepository subjectRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private StatsService statsService;

    private static final Long USER = 1L;

    private static Subject subject(Long id, String name, int targetPerWeek) {
        Subject s = new Subject(USER, name, "#4F46E5", targetPerWeek, 0);
        s.setId(id);
        return s;
    }

    private static User user(LocalDate examDate) {
        User u = new User("darling", "$2a$10$hash", "备考中的我", examDate);
        u.setId(USER);
        return u;
    }

    @Nested
    @DisplayName("仪表盘总览")
    class Overview {

        @Test
        @DisplayName("★ 今日没有任何任务 -> 完成率 0.0（不是 1.0，也不是 NaN）")
        void zeroDenominatorYieldsZeroRate() {
            when(userRepository.findById(USER)).thenReturn(Optional.of(user(null)));

            StatsDtos.OverviewResponse response = statsService.overview(USER);

            assertThat(response.todayTotalTasks()).isZero();
            assertThat(response.todayCompletionRate()).isZero();
            assertThat(response.todayCompletionRate()).isNotNaN();
            assertThat(response.date()).isEqualTo(LocalDate.now());
        }

        @Test
        @DisplayName("今日完成率保留两位小数（2/3 -> 0.67，不是 0.6666666666666666）")
        void completionRateIsRounded() {
            LocalDate today = LocalDate.now();
            when(userRepository.findById(USER)).thenReturn(Optional.of(user(null)));
            when(taskRepository.countByUserIdAndPlanDate(USER, today)).thenReturn(3L);
            when(taskRepository.countByUserIdAndPlanDateAndStatus(USER, today, TaskStatus.DONE))
                    .thenReturn(2L);

            assertThat(statsService.overview(USER).todayCompletionRate()).isEqualTo(0.67);
        }

        @Test
        @DisplayName("★ 连续打卡天数来自 StreakCalculator，累计天数 = 有记录的自然日个数")
        void streakIsComputed() {
            LocalDate today = LocalDate.now();
            when(userRepository.findById(USER)).thenReturn(Optional.of(user(null)));
            when(checkinRepository.findDistinctCheckinDatesDesc(USER))
                    .thenReturn(List.of(today, today.minusDays(1), today.minusDays(2)));

            StatsDtos.OverviewResponse response = statsService.overview(USER);

            assertThat(response.currentStreakDays()).isEqualTo(3);
            assertThat(response.longestStreakDays()).isEqualTo(3);
            assertThat(response.totalCheckinDays()).isEqualTo(3);
            assertThat(response.lastCheckinDate()).isEqualTo(today);
        }

        @Test
        @DisplayName("从未打卡 -> 累计天数 0，最近打卡日期 null（不是 1970-01-01）")
        void neverCheckedIn() {
            when(userRepository.findById(USER)).thenReturn(Optional.of(user(null)));

            StatsDtos.OverviewResponse response = statsService.overview(USER);

            assertThat(response.totalCheckinDays()).isZero();
            assertThat(response.lastCheckinDate()).isNull();
            assertThat(response.currentStreakDays()).isZero();
        }

        @Test
        @DisplayName("★ 用户不存在 -> 404")
        void missingUser() {
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> statsService.overview(99L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.NOT_FOUND));
        }

        @Test
        @DisplayName("没设考试日期 -> 倒计时 null；已过 -> 负数")
        void examCountdown() {
            when(userRepository.findById(USER)).thenReturn(Optional.of(user(null)));
            assertThat(statsService.overview(USER).daysUntilExam()).isNull();

            when(userRepository.findById(USER))
                    .thenReturn(Optional.of(user(LocalDate.now().minusDays(3))));
            StatsDtos.OverviewResponse past = statsService.overview(USER);
            assertThat(past.daysUntilExam()).isEqualTo(-3L);
            assertThat(past.examDate()).isEqualTo(LocalDate.now().minusDays(3));
        }
    }

    @Nested
    @DisplayName("四科看板")
    class SubjectBoard {

        @Test
        @DisplayName("★ 天数越界 -> 400（挡住 ?days=100000 这种请求）")
        void rejectsOutOfRangeDays() {
            assertThatThrownBy(() -> statsService.subjectBoard(USER, 0))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_PARAM));

            assertThatThrownBy(() -> statsService.subjectBoard(USER, -7))
                    .isInstanceOf(BusinessException.class);

            assertThatThrownBy(() -> statsService.subjectBoard(USER, 366))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("365");
        }

        @Test
        @DisplayName("不传天数 -> 默认 7 天，且回显出来（前端不用硬编码）")
        void defaultPeriodIsSevenDays() {
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER)).thenReturn(List.of());

            StatsDtos.SubjectBoardResponse response = statsService.subjectBoard(USER, null);

            assertThat(response.periodDays()).isEqualTo(7);
            assertThat(response.to()).isEqualTo(LocalDate.now());
            assertThat(response.from()).isEqualTo(LocalDate.now().minusDays(6));
        }

        @Test
        @DisplayName("窗口区间正确：days=30 -> from = today-29, to = today（闭区间）")
        void windowIsInclusive() {
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER)).thenReturn(List.of());

            StatsDtos.SubjectBoardResponse response = statsService.subjectBoard(USER, 30);

            assertThat(response.to()).isEqualTo(LocalDate.now());
            assertThat(response.from()).isEqualTo(LocalDate.now().minusDays(29));
        }

        @Test
        @DisplayName("★ 周目标按窗口折算：420 分钟/周，7 天 -> 420；5 天 -> 300；1 天 -> 60")
        void weeklyTargetIsScaledToWindow() {
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(10L, "数学", 420)));

            assertThat(targetForDays(7)).isEqualTo(420);
            assertThat(targetForDays(5)).isEqualTo(300);
            assertThat(targetForDays(1)).isEqualTo(60);
            assertThat(targetForDays(30)).isEqualTo(1800);
        }

        @Test
        @DisplayName("★ 折算用 double 再四舍五入：100/周 × 3 天 = 42.857 -> 43（不是整除的 42）")
        void scalingRoundsInsteadOfTruncating() {
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(10L, "数学", 100)));

            assertThat(targetForDays(3)).isEqualTo(43);
        }

        private long targetForDays(int days) {
            return statsService.subjectBoard(USER, days)
                    .subjects().get(0).targetMinutesInPeriod();
        }

        @Test
        @DisplayName("没打过卡的科目 -> 最近打卡日期 null，达成率 0.0（不是 NaN）")
        void subjectWithoutCheckins() {
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(10L, "数学", 420)));

            SubjectDtos.SubjectProgressResponse row =
                    statsService.subjectBoard(USER, 7).subjects().get(0);

            assertThat(row.lastCheckinDate()).isNull();
            assertThat(row.periodMinutes()).isZero();
            assertThat(row.achievementRate()).isZero();
            assertThat(row.achievementRate()).isNotNaN();
        }

        @Test
        @DisplayName("★ 达成率保留两位小数，且允许超过 1（超额完成不该被截断成 1.0）")
        void achievementRateCanExceedOne() {
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(10L, "数学", 420)));
            when(checkinRepository.sumMinutesGroupBySubject(eq(USER), any(), any()))
                    .thenReturn(List.of(new SubjectMinutes(10L, 630L)));

            SubjectDtos.SubjectProgressResponse row =
                    statsService.subjectBoard(USER, 7).subjects().get(0);

            assertThat(row.targetMinutesInPeriod()).isEqualTo(420);
            assertThat(row.periodMinutes()).isEqualTo(630);
            assertThat(row.achievementRate()).isEqualTo(1.5);
        }

        @Test
        @DisplayName("任务数与完成率：4 个任务完成 3 个 -> 0.75，未完成 1 个")
        void taskCounts() {
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(10L, "数学", 420)));
            when(taskRepository.countGroupBySubject(USER))
                    .thenReturn(List.of(new SubjectCount(10L, 4L)));
            when(taskRepository.countGroupBySubjectAndStatus(USER, TaskStatus.DONE))
                    .thenReturn(List.of(new SubjectCount(10L, 3L)));

            SubjectDtos.SubjectProgressResponse row =
                    statsService.subjectBoard(USER, 7).subjects().get(0);

            assertThat(row.taskTotal()).isEqualTo(4);
            assertThat(row.taskDone()).isEqualTo(3);
            assertThat(row.taskPending()).isEqualTo(1);
            assertThat(row.completionRate()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("某科目从没打过卡 -> 最近打卡日期为 null（聚合结果里没有它）")
        void lastCheckinDate() {
            LocalDate last = LocalDate.now().minusDays(4);
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(10L, "数学", 420), subject(11L, "英语", 300)));
            when(checkinRepository.findLastCheckinDateGroupBySubject(USER))
                    .thenReturn(List.of(new SubjectLastCheckin(10L, last)));

            List<SubjectDtos.SubjectProgressResponse> rows =
                    statsService.subjectBoard(USER, 7).subjects();

            assertThat(rows.get(0).lastCheckinDate()).isEqualTo(last);
            assertThat(rows.get(1).lastCheckinDate()).isNull();
        }

        @Test
        @DisplayName("汇总：窗口内实际总投入、应投入总量、整体达成率")
        void overallTotals() {
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(10L, "数学", 420), subject(11L, "英语", 140)));
            when(checkinRepository.sumMinutesGroupBySubject(eq(USER), any(), any()))
                    .thenReturn(List.of(new SubjectMinutes(10L, 420L), new SubjectMinutes(11L, 0L)));

            StatsDtos.SubjectBoardResponse response = statsService.subjectBoard(USER, 7);

            assertThat(response.periodTotalMinutes()).isEqualTo(420);
            assertThat(response.periodTargetMinutes()).isEqualTo(560);
            assertThat(response.overallAchievementRate()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("所有科目周目标都是 0 -> 整体达成率 0.0（不除零）")
        void zeroOverallTarget() {
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(10L, "数学", 0)));

            StatsDtos.SubjectBoardResponse response = statsService.subjectBoard(USER, 7);

            assertThat(response.periodTargetMinutes()).isZero();
            assertThat(response.overallAchievementRate()).isZero();
            assertThat(response.overallAchievementRate()).isNotNaN();
        }

        @Test
        @DisplayName("聚合查询会收到正确的窗口区间（下推到数据库，不是查出来再在内存里筛）")
        void aggregatesUseTheWindow() {
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER)).thenReturn(List.of());

            statsService.subjectBoard(USER, 14);

            ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
            ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
            verify(checkinRepository).sumMinutesGroupBySubject(eq(USER), from.capture(), to.capture());

            assertThat(to.getValue()).isEqualTo(LocalDate.now());
            assertThat(from.getValue()).isEqualTo(LocalDate.now().minusDays(13));
        }

        @Test
        @DisplayName("没有科目 -> 返回空列表而不是 null")
        void emptySubjects() {
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER)).thenReturn(List.of());

            StatsDtos.SubjectBoardResponse response = statsService.subjectBoard(USER, 7);

            assertThat(response.subjects()).isNotNull().isEmpty();
        }
    }
}
