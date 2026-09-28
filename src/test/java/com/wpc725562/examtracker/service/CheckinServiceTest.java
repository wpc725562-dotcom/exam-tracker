package com.wpc725562.examtracker.service;

import com.wpc725562.examtracker.common.BusinessException;
import com.wpc725562.examtracker.common.ErrorCode;
import com.wpc725562.examtracker.common.PageResult;
import com.wpc725562.examtracker.domain.Checkin;
import com.wpc725562.examtracker.domain.Subject;
import com.wpc725562.examtracker.domain.Task;
import com.wpc725562.examtracker.dto.CheckinDtos;
import com.wpc725562.examtracker.repository.CheckinRepository;
import com.wpc725562.examtracker.repository.SubjectRepository;
import com.wpc725562.examtracker.repository.TaskRepository;
import com.wpc725562.examtracker.repository.projection.DateMinutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 打卡记录的增删查。
 *
 * <p>重点：
 * <ul>
 *   <li><b>科目从任务推导</b> —— 传了 taskId 就忽略 subjectId，
 *       让「打卡的科目和任务的科目不一致」这种数据**在结构上无法产生**；</li>
 *   <li><b>不拉全表</b> —— 不传日期时默认只回溯 30 天；</li>
 *   <li><b>不为整库任务查标题</b> —— 只查当前页出现的那几个任务 id，而且按 userId 过滤。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CheckinService")
class CheckinServiceTest {

    @Mock
    private CheckinRepository checkinRepository;
    @Mock
    private SubjectRepository subjectRepository;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private SubjectService subjectService;

    @InjectMocks
    private CheckinService checkinService;

    private static final Long USER = 1L;
    private static final Long SUBJECT = 10L;

    private static Subject subject(Long id, String name) {
        Subject s = new Subject(USER, name, null, 420, 0);
        s.setId(id);
        return s;
    }

    private static Task task(Long id, Long subjectId, String title) {
        Task t = new Task(USER, subjectId, title, LocalDate.of(2026, 9, 28), 90, null, null);
        t.setId(id);
        return t;
    }

    private static Checkin checkin(Long id, Long subjectId, Long taskId, LocalDate date, int minutes) {
        Checkin c = new Checkin(USER, subjectId, taskId, date, minutes, null);
        c.setId(id);
        return c;
    }

    private static CheckinDtos.CreateRequest req(Long subjectId, Long taskId,
                                                 LocalDate date, int minutes) {
        return new CheckinDtos.CreateRequest(subjectId, taskId, date, minutes, null);
    }

    @Nested
    @DisplayName("新增打卡")
    class Create {

        @Test
        @DisplayName("★ subjectId 和 taskId 都不传 -> 400")
        void needsAtLeastOneAnchor() {
            assertThatThrownBy(() -> checkinService.create(USER, req(null, null, LocalDate.now(), 60)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("至少要填一个")
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_PARAM));

            verify(checkinRepository, never()).save(any(Checkin.class));
        }

        @Test
        @DisplayName("★ 挂到别人的任务上 -> 404（不区分「不存在」和「是别人的」）")
        void cannotAttachToOthersTask() {
            when(taskRepository.findByIdAndUserId(77L, USER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> checkinService.create(USER, req(null, 77L, LocalDate.now(), 60)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.NOT_FOUND));

            verify(checkinRepository, never()).save(any(Checkin.class));
        }

        @Test
        @DisplayName("★ 同时传 taskId 和矛盾的 subjectId -> 以任务为准（不产生矛盾数据）")
        void subjectIsDerivedFromTask() {
            when(taskRepository.findByIdAndUserId(5L, USER))
                    .thenReturn(Optional.of(task(5L, SUBJECT, "真题")));
            when(checkinRepository.save(any(Checkin.class))).thenAnswer(inv -> {
                Checkin c = inv.getArgument(0);
                c.setId(100L);
                return c;
            });
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(SUBJECT, "数学")));

            // 打卡说科目是 99（英语），任务实际属于 10（数学）
            CheckinDtos.CheckinResponse response =
                    checkinService.create(USER, req(99L, 5L, LocalDate.now(), 60));

            ArgumentCaptor<Checkin> saved = ArgumentCaptor.forClass(Checkin.class);
            verify(checkinRepository).save(saved.capture());
            assertThat(saved.getValue().getSubjectId()).isEqualTo(SUBJECT);
            assertThat(response.subjectId()).isEqualTo(SUBJECT);
            // 也不应该去查科目 99 是否存在
            verify(subjectService, never()).requireSubject(anyLong(), anyLong());
        }

        @Test
        @DisplayName("只传 subjectId -> 校验科目归属后落库")
        void subjectOnlyPath() {
            when(subjectService.requireSubject(USER, SUBJECT)).thenReturn(subject(SUBJECT, "数学"));
            when(checkinRepository.save(any(Checkin.class))).thenAnswer(inv -> {
                Checkin c = inv.getArgument(0);
                c.setId(100L);
                return c;
            });
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(SUBJECT, "数学")));

            CheckinDtos.CheckinResponse response =
                    checkinService.create(USER, req(SUBJECT, null, LocalDate.now(), 60));

            assertThat(response.taskId()).isNull();
            assertThat(response.taskTitle()).isNull();
            assertThat(response.subjectName()).isEqualTo("数学");
        }

        @Test
        @DisplayName("★ 未来日期 -> 400（不能在「明天」打卡）")
        void futureDateIsRejected() {
            when(subjectService.requireSubject(USER, SUBJECT)).thenReturn(subject(SUBJECT, "数学"));

            assertThatThrownBy(() -> checkinService.create(
                    USER, req(SUBJECT, null, LocalDate.now().plusDays(1), 60)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("未来");

            verify(checkinRepository, never()).save(any(Checkin.class));
        }

        @Test
        @DisplayName("不传日期 -> 用服务端的今天（不信客户端的钟）")
        void defaultsToToday() {
            when(subjectService.requireSubject(USER, SUBJECT)).thenReturn(subject(SUBJECT, "数学"));
            when(checkinRepository.save(any(Checkin.class))).thenAnswer(inv -> {
                Checkin c = inv.getArgument(0);
                c.setId(100L);
                return c;
            });
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(SUBJECT, "数学")));

            CheckinDtos.CheckinResponse response =
                    checkinService.create(USER, req(SUBJECT, null, null, 60));

            assertThat(response.checkinDate()).isEqualTo(LocalDate.now());
        }

        @Test
        @DisplayName("今天可以打卡（边界：不是「未来」）")
        void todayIsAllowed() {
            when(subjectService.requireSubject(USER, SUBJECT)).thenReturn(subject(SUBJECT, "数学"));
            when(checkinRepository.save(any(Checkin.class))).thenAnswer(inv -> {
                Checkin c = inv.getArgument(0);
                c.setId(100L);
                return c;
            });
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(SUBJECT, "数学")));

            checkinService.create(USER, req(SUBJECT, null, LocalDate.now(), 60));

            verify(checkinRepository).save(any(Checkin.class));
        }

        @Test
        @DisplayName("备注只有空白 -> 存 null")
        void blankNoteBecomesNull() {
            when(subjectService.requireSubject(USER, SUBJECT)).thenReturn(subject(SUBJECT, "数学"));
            when(checkinRepository.save(any(Checkin.class))).thenAnswer(inv -> {
                Checkin c = inv.getArgument(0);
                c.setId(100L);
                return c;
            });
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(SUBJECT, "数学")));

            checkinService.create(USER, new CheckinDtos.CreateRequest(
                    SUBJECT, null, LocalDate.now(), 60, "   "));

            ArgumentCaptor<Checkin> saved = ArgumentCaptor.forClass(Checkin.class);
            verify(checkinRepository).save(saved.capture());
            assertThat(saved.getValue().getNote()).isNull();
        }

        @Test
        @DisplayName("挂在任务上的打卡会带上任务标题")
        void returnsTaskTitle() {
            when(taskRepository.findByIdAndUserId(5L, USER))
                    .thenReturn(Optional.of(task(5L, SUBJECT, "真题")));
            when(checkinRepository.save(any(Checkin.class))).thenAnswer(inv -> {
                Checkin c = inv.getArgument(0);
                c.setId(100L);
                return c;
            });
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(SUBJECT, "数学")));
            when(taskRepository.findAllById(Set.of(5L)))
                    .thenReturn(List.of(task(5L, SUBJECT, "真题")));

            assertThat(checkinService.create(USER, req(null, 5L, LocalDate.now(), 60)).taskTitle())
                    .isEqualTo("真题");
        }
    }

    @Nested
    @DisplayName("列表")
    class ListCheckins {

        @Test
        @DisplayName("单页超过 200 条 -> 400")
        void rejectsOversizedPage() {
            assertThatThrownBy(() -> checkinService.list(USER, null, null, null, 1, 201))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_PARAM));

            verify(checkinRepository, never())
                    .findByUserIdAndCheckinDateBetween(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("开始日期晚于结束日期 -> 400")
        void rejectsInvertedRange() {
            assertThatThrownBy(() -> checkinService.list(
                    USER, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1), null, 1, 20))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能晚于");
        }

        @Test
        @DisplayName("★ 不传日期 -> 默认只回溯 30 天（打开页面不会拉全表）")
        void defaultWindowIsThirtyDays() {
            when(checkinRepository.findByUserIdAndCheckinDateBetween(
                    eq(USER), any(), any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            checkinService.list(USER, null, null, null, 1, 20);

            ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
            ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
            verify(checkinRepository)
                    .findByUserIdAndCheckinDateBetween(eq(USER), from.capture(), to.capture(),
                            any(Pageable.class));

            LocalDate today = LocalDate.now();
            assertThat(to.getValue()).isEqualTo(today);
            assertThat(from.getValue()).isEqualTo(today.minusDays(29));
        }

        @Test
        @DisplayName("传了日期就用传的")
        void explicitRangeWins() {
            LocalDate from = LocalDate.of(2026, 8, 1);
            LocalDate to = LocalDate.of(2026, 8, 31);
            when(checkinRepository.findByUserIdAndCheckinDateBetween(
                    eq(USER), eq(from), eq(to), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            checkinService.list(USER, from, to, null, 1, 20);

            verify(checkinRepository).findByUserIdAndCheckinDateBetween(
                    eq(USER), eq(from), eq(to), any(Pageable.class));
        }

        @Test
        @DisplayName("传了科目 -> 走带科目过滤的查询")
        void filtersBySubject() {
            when(checkinRepository.findByUserIdAndSubjectIdAndCheckinDateBetween(
                    eq(USER), eq(SUBJECT), any(), any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            checkinService.list(USER, null, null, SUBJECT, 1, 20);

            verify(checkinRepository).findByUserIdAndSubjectIdAndCheckinDateBetween(
                    eq(USER), eq(SUBJECT), any(), any(), any(Pageable.class));
            verify(checkinRepository, never())
                    .findByUserIdAndCheckinDateBetween(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("★ 只为本页出现的任务查标题（不是把用户所有任务拉出来）")
        void onlyLooksUpTasksOnThisPage() {
            LocalDate today = LocalDate.now();
            Page<Checkin> page = new PageImpl<>(
                    List.of(checkin(1L, SUBJECT, 5L, today, 60),
                            checkin(2L, SUBJECT, 6L, today, 30),
                            checkin(3L, SUBJECT, null, today, 20)),
                    PageRequest.of(0, 20), 3);
            when(checkinRepository.findByUserIdAndCheckinDateBetween(
                    eq(USER), any(), any(), any(Pageable.class))).thenReturn(page);
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(SUBJECT, "数学")));
            when(taskRepository.findAllById(any())).thenReturn(List.of(
                    task(5L, SUBJECT, "真题"), task(6L, SUBJECT, "背单词")));

            PageResult<CheckinDtos.CheckinResponse> result =
                    checkinService.list(USER, null, null, null, 1, 20);

            ArgumentCaptor<Iterable<Long>> ids = ArgumentCaptor.forClass(Iterable.class);
            verify(taskRepository, times(1)).findAllById(ids.capture());
            assertThat(ids.getValue()).containsExactlyInAnyOrder(5L, 6L);

            assertThat(result.items()).extracting(CheckinDtos.CheckinResponse::taskTitle)
                    .containsExactly("真题", "背单词", null);
        }

        @Test
        @DisplayName("★ 残留的「别人的任务 id」不会把别人的标题读出来")
        void taskTitlesAreFilteredByUserId() {
            LocalDate today = LocalDate.now();
            Page<Checkin> page = new PageImpl<>(
                    List.of(checkin(1L, SUBJECT, 5L, today, 60)),
                    PageRequest.of(0, 20), 1);
            when(checkinRepository.findByUserIdAndCheckinDateBetween(
                    eq(USER), any(), any(), any(Pageable.class))).thenReturn(page);
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(SUBJECT, "数学")));

            Task someoneElses = new Task(999L, SUBJECT, "别人的任务", today, 90, null, null);
            someoneElses.setId(5L);
            when(taskRepository.findAllById(any())).thenReturn(List.of(someoneElses));

            PageResult<CheckinDtos.CheckinResponse> result =
                    checkinService.list(USER, null, null, null, 1, 20);

            assertThat(result.items().get(0).taskTitle()).isNull();
        }

        @Test
        @DisplayName("本页没有关联任务时不查任务表")
        void noTaskLookupWhenNoTaskIds() {
            LocalDate today = LocalDate.now();
            when(checkinRepository.findByUserIdAndCheckinDateBetween(
                    eq(USER), any(), any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(checkin(1L, SUBJECT, null, today, 60)),
                            PageRequest.of(0, 20), 1));
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(SUBJECT, "数学")));

            checkinService.list(USER, null, null, null, 1, 20);

            verify(taskRepository, never()).findAllById(any());
        }

        @Test
        @DisplayName("科目已删 -> 兜底文案")
        void deletedSubjectFallsBack() {
            LocalDate today = LocalDate.now();
            when(checkinRepository.findByUserIdAndCheckinDateBetween(
                    eq(USER), any(), any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(checkin(1L, SUBJECT, null, today, 60)),
                            PageRequest.of(0, 20), 1));
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER)).thenReturn(List.of());

            assertThat(checkinService.list(USER, null, null, null, 1, 20)
                    .items().get(0).subjectName()).isEqualTo("（科目已删除）");
        }
    }

    @Nested
    @DisplayName("日历视图")
    class DailyMinutes {

        @Test
        @DisplayName("按天聚合，minutes 为 null 时兜成 0")
        void aggregatesByDay() {
            when(checkinRepository.sumMinutesGroupByDate(eq(USER), any(), any()))
                    .thenReturn(List.of(
                            new DateMinutes(LocalDate.of(2026, 9, 27), 120L),
                            new DateMinutes(LocalDate.of(2026, 9, 28), null)));

            List<CheckinDtos.DailyMinutes> result = checkinService.dailyMinutes(USER, null, null);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).minutes()).isEqualTo(120L);
            assertThat(result.get(1).minutes()).isZero();
        }

        @Test
        @DisplayName("日期区间反了 -> 400")
        void invertedRange() {
            assertThatThrownBy(() -> checkinService.dailyMinutes(
                    USER, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1)))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("删除")
    class Delete {

        @Test
        @DisplayName("★ 删别人的打卡 -> 404")
        void cannotDeleteOthers() {
            when(checkinRepository.findByIdAndUserId(9L, USER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> checkinService.delete(USER, 9L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.NOT_FOUND));

            verify(checkinRepository, never()).delete(any(Checkin.class));
        }

        @Test
        @DisplayName("删自己的打卡")
        void deletesOwn() {
            Checkin own = checkin(9L, SUBJECT, null, LocalDate.now(), 60);
            when(checkinRepository.findByIdAndUserId(9L, USER)).thenReturn(Optional.of(own));

            checkinService.delete(USER, 9L);

            verify(checkinRepository).delete(own);
        }
    }
}
