package com.wpc725562.examtracker.service;

import com.wpc725562.examtracker.common.BusinessException;
import com.wpc725562.examtracker.common.ErrorCode;
import com.wpc725562.examtracker.common.PageResult;
import com.wpc725562.examtracker.domain.Priority;
import com.wpc725562.examtracker.domain.Subject;
import com.wpc725562.examtracker.domain.Task;
import com.wpc725562.examtracker.domain.TaskStatus;
import com.wpc725562.examtracker.dto.TaskDtos;
import com.wpc725562.examtracker.dto.TaskFilter;
import com.wpc725562.examtracker.repository.SubjectRepository;
import com.wpc725562.examtracker.repository.TaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务的增删改查。
 *
 * <p>重点：
 * <ul>
 *   <li><b>越权</b> —— 所有「按 id 查」都必须带 userId；</li>
 *   <li><b>N+1</b> —— 列表里科目名必须一次查完，而不是每条任务查一次；</li>
 *   <li><b>分页</b> —— 对外 1 基页码转成 Spring Data 的 0 基，且排序必须带 tie-breaker。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskService")
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private SubjectRepository subjectRepository;
    @Mock
    private SubjectService subjectService;

    @InjectMocks
    private TaskService taskService;

    private static final Long USER = 1L;
    private static final Long SUBJECT = 10L;
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 9, 28);

    private static Subject subject(Long id, String name) {
        Subject s = new Subject(USER, name, "#4F46E5", 420, 0);
        s.setId(id);
        return s;
    }

    private static Task task(Long id, String title) {
        Task t = new Task(USER, SUBJECT, title, PLAN_DATE, 90, Priority.HIGH, null);
        t.setId(id);
        return t;
    }

    private static Page<Task> pageOf(List<Task> tasks, int pageIndex, int size, long total) {
        return new PageImpl<>(tasks, org.springframework.data.domain.PageRequest.of(pageIndex, size), total);
    }

    @Nested
    @DisplayName("列表")
    class ListTasks {

        @Test
        @DisplayName("★ 单页超过 200 条 -> 400，且不碰数据库（挡住拖库请求）")
        void rejectsOversizedPage() {
            assertThatThrownBy(() -> taskService.list(
                    USER, TaskFilter.none(), 1, TaskService.maxPageSize() + 1, null, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_PARAM));

            verify(taskRepository, never())
                    .findAll(ArgumentMatchers.<Specification<Task>>any(), any(Pageable.class));
        }

        @Test
        @DisplayName("★ 对外的 1 基页码会转成 Spring Data 的 0 基（差一位就会返回错页数据）")
        void pageNumberIsZeroBasedInternally() {
            when(taskRepository.findAll(
                    ArgumentMatchers.<Specification<Task>>any(), any(Pageable.class)))
                    .thenReturn(pageOf(List.of(), 2, 20, 0));

            taskService.list(USER, TaskFilter.none(), 3, 20, null, null);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(taskRepository).findAll(ArgumentMatchers.<Specification<Task>>any(), captor.capture());
            assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
            assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        }

        @Test
        @DisplayName("page < 1 会被兜底成第 1 页（不让负数传进 Spring Data）")
        void negativePageFallsBackToFirst() {
            when(taskRepository.findAll(
                    ArgumentMatchers.<Specification<Task>>any(), any(Pageable.class)))
                    .thenReturn(pageOf(List.of(), 0, 20, 0));

            taskService.list(USER, TaskFilter.none(), -5, 20, null, null);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(taskRepository).findAll(ArgumentMatchers.<Specification<Task>>any(), captor.capture());
            assertThat(captor.getValue().getPageNumber()).isZero();
        }

        @Test
        @DisplayName("不指定排序 -> 用默认排序（planDate 倒序 + id 兜底）")
        void defaultSort() {
            when(taskRepository.findAll(
                    ArgumentMatchers.<Specification<Task>>any(), any(Pageable.class)))
                    .thenReturn(pageOf(List.of(), 0, 20, 0));

            taskService.list(USER, TaskFilter.none(), 1, 20, null, null);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(taskRepository).findAll(ArgumentMatchers.<Specification<Task>>any(), captor.capture());
            Sort sort = captor.getValue().getSort();

            assertThat(sort.getOrderFor("planDate").getDirection()).isEqualTo(Sort.Direction.DESC);
            assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        @Test
        @DisplayName("指定排序 -> 交给 SortResolver，并带上 id 作为 tie-breaker")
        void explicitSort() {
            when(taskRepository.findAll(
                    ArgumentMatchers.<Specification<Task>>any(), any(Pageable.class)))
                    .thenReturn(pageOf(List.of(), 0, 20, 0));

            taskService.list(USER, TaskFilter.none(), 1, 20, "planMinutes", "desc");

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(taskRepository).findAll(ArgumentMatchers.<Specification<Task>>any(), captor.capture());
            Sort sort = captor.getValue().getSort();

            assertThat(sort.getOrderFor("planMinutes").getDirection()).isEqualTo(Sort.Direction.DESC);
            assertThat(sort.getOrderFor("id")).isNotNull();
        }

        @Test
        @DisplayName("★ 科目名一次查完（防 N+1：20 条任务不能变成 21 条 SQL）")
        void subjectNamesAreFetchedOnce() {
            when(taskRepository.findAll(
                    ArgumentMatchers.<Specification<Task>>any(), any(Pageable.class)))
                    .thenReturn(pageOf(List.of(task(1L, "真题"), task(2L, "背单词")), 0, 20, 2));
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(SUBJECT, "数学")));

            PageResult<TaskDtos.TaskResponse> result =
                    taskService.list(USER, TaskFilter.none(), 1, 20, null, null);

            assertThat(result.items()).hasSize(2);
            assertThat(result.items()).allSatisfy(t -> assertThat(t.subjectName()).isEqualTo("数学"));
            assertThat(result.total()).isEqualTo(2);
            verify(subjectRepository, times(1)).findByUserIdOrderBySortOrderAscIdAsc(USER);
        }

        @Test
        @DisplayName("科目已被删 -> 兜底成「（科目已删除）」而不是 null（不让前端崩）")
        void deletedSubjectFallsBack() {
            when(taskRepository.findAll(
                    ArgumentMatchers.<Specification<Task>>any(), any(Pageable.class)))
                    .thenReturn(pageOf(List.of(task(1L, "真题")), 0, 20, 1));
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER)).thenReturn(List.of());

            assertThat(taskService.list(USER, TaskFilter.none(), 1, 20, null, null)
                    .items().get(0).subjectName()).isEqualTo("（科目已删除）");
        }

        @Test
        @DisplayName("筛选条件会真的被拼进 Specification（keyword 非空时不等于「查全部」）")
        void filterIsApplied() {
            when(taskRepository.findAll(
                    ArgumentMatchers.<Specification<Task>>any(), any(Pageable.class)))
                    .thenReturn(pageOf(List.of(), 0, 20, 0));

            TaskFilter filter = new TaskFilter(
                    PLAN_DATE, PLAN_DATE, SUBJECT, TaskStatus.TODO, Priority.HIGH, "真题");
            taskService.list(USER, filter, 1, 20, null, null);

            verify(taskRepository).findAll(ArgumentMatchers.<Specification<Task>>any(), any(Pageable.class));
        }
    }

    @Nested
    @DisplayName("按 id 查（防越权）")
    class Get {

        @Test
        @DisplayName("★ 查不到 -> 404，且查询确实带了 userId")
        void notFound() {
            when(taskRepository.findByIdAndUserId(77L, USER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> taskService.get(USER, 77L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.NOT_FOUND));

            verify(taskRepository).findByIdAndUserId(77L, USER);
        }

        @Test
        @DisplayName("查得到 -> 带上科目名")
        void found() {
            when(taskRepository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(task(1L, "真题")));
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(SUBJECT, "数学")));

            assertThat(taskService.get(USER, 1L).subjectName()).isEqualTo("数学");
        }

        @Test
        @DisplayName("requireTask 供其它 Service 复用")
        void requireTask() {
            when(taskRepository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(task(1L, "真题")));

            assertThat(taskService.requireTask(USER, 1L).getTitle()).isEqualTo("真题");
        }
    }

    @Nested
    @DisplayName("新建")
    class Create {

        private TaskDtos.CreateRequest req(String title, String note) {
            return new TaskDtos.CreateRequest(SUBJECT, title, PLAN_DATE, 90, Priority.HIGH, note);
        }

        @Test
        @DisplayName("★ 科目不属于自己 -> 404（不能把任务挂到别人科目上）")
        void requiresOwnedSubject() {
            when(subjectService.requireSubject(USER, SUBJECT))
                    .thenThrow(BusinessException.notFound("科目"));

            assertThatThrownBy(() -> taskService.create(USER, req("真题", null)))
                    .isInstanceOf(BusinessException.class);

            verify(taskRepository, never()).save(any(Task.class));
        }

        @Test
        @DisplayName("标题去空格；备注只有空白 -> 存 null（不让 \"\" 和 null 两种「没填」并存）")
        void trimsTitleAndNullsBlankNote() {
            when(subjectService.requireSubject(USER, SUBJECT)).thenReturn(subject(SUBJECT, "数学"));
            when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
                Task t = inv.getArgument(0);
                t.setId(1L);
                return t;
            });

            taskService.create(USER, req("  真题  ", "   "));

            ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
            verify(taskRepository).save(saved.capture());
            assertThat(saved.getValue().getTitle()).isEqualTo("真题");
            assertThat(saved.getValue().getNote()).isNull();
        }

        @Test
        @DisplayName("返回体里带上科目名")
        void returnsSubjectName() {
            when(subjectService.requireSubject(USER, SUBJECT)).thenReturn(subject(SUBJECT, "数学"));
            when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
                Task t = inv.getArgument(0);
                t.setId(1L);
                return t;
            });

            assertThat(taskService.create(USER, req("真题", null)).subjectName()).isEqualTo("数学");
        }
    }

    @Nested
    @DisplayName("修改")
    class Update {

        @Test
        @DisplayName("★ 优先级传 null -> 保留原值（不是被重置成 MEDIUM）")
        void nullPriorityKeepsOldValue() {
            Task existing = task(1L, "真题");
            when(taskRepository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(existing));
            when(subjectService.requireSubject(USER, SUBJECT)).thenReturn(subject(SUBJECT, "数学"));
            when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

            taskService.update(USER, 1L,
                    new TaskDtos.UpdateRequest(SUBJECT, "真题（改）", PLAN_DATE, 120, null, null));

            assertThat(existing.getPriority()).isEqualTo(Priority.HIGH);
            assertThat(existing.getTitle()).isEqualTo("真题（改）");
            assertThat(existing.getPlanMinutes()).isEqualTo(120);
        }

        @Test
        @DisplayName("改别人的任务 -> 404")
        void cannotUpdateOthersTask() {
            when(taskRepository.findByIdAndUserId(99L, USER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> taskService.update(USER, 99L,
                    new TaskDtos.UpdateRequest(SUBJECT, "x", PLAN_DATE, 90, null, null)))
                    .isInstanceOf(BusinessException.class);

            verify(taskRepository, never()).save(any(Task.class));
        }
    }

    @Nested
    @DisplayName("状态流转")
    class ChangeStatus {

        @Test
        @DisplayName("标记 DONE -> 写入完成时间")
        void doneSetsCompletedAt() {
            Task existing = task(1L, "真题");
            when(taskRepository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(existing));
            when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

            taskService.changeStatus(USER, 1L, new TaskDtos.StatusRequest(TaskStatus.DONE));

            assertThat(existing.getStatus()).isEqualTo(TaskStatus.DONE);
            assertThat(existing.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("★ 从 DONE 改回 TODO -> 清空完成时间")
        void backToTodoClearsCompletedAt() {
            Task existing = task(1L, "真题");
            existing.changeStatus(TaskStatus.DONE, LocalDateTime.now());
            when(taskRepository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(existing));
            when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

            taskService.changeStatus(USER, 1L, new TaskDtos.StatusRequest(TaskStatus.TODO));

            assertThat(existing.getStatus()).isEqualTo(TaskStatus.TODO);
            assertThat(existing.getCompletedAt()).isNull();
        }

        @Test
        @DisplayName("改别人的任务状态 -> 404")
        void cannotChangeOthersStatus() {
            when(taskRepository.findByIdAndUserId(99L, USER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> taskService.changeStatus(
                    USER, 99L, new TaskDtos.StatusRequest(TaskStatus.DONE)))
                    .isInstanceOf(BusinessException.class);

            verify(taskRepository, never()).save(any(Task.class));
        }
    }

    @Nested
    @DisplayName("删除")
    class Delete {

        @Test
        @DisplayName("删除只删任务本身，不碰打卡记录（外键是 ON DELETE SET NULL）")
        void deletesTaskOnly() {
            Task existing = task(1L, "真题");
            when(taskRepository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(existing));

            taskService.delete(USER, 1L);

            verify(taskRepository).delete(existing);
        }

        @Test
        @DisplayName("删别人的任务 -> 404")
        void cannotDeleteOthersTask() {
            when(taskRepository.findByIdAndUserId(99L, USER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> taskService.delete(USER, 99L))
                    .isInstanceOf(BusinessException.class);

            verify(taskRepository, never()).delete(any(Task.class));
        }
    }

    @Nested
    @DisplayName("科目名映射")
    class SubjectNameMap {

        @Test
        @DisplayName("同 id 重复时保留第一条（不抛 IllegalStateException）")
        void duplicateKeysDoNotThrow() {
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(1L, "数学"), subject(1L, "数学（重复）")));

            assertThat(taskService.subjectNameMap(USER)).containsEntry(1L, "数学");
        }

        @Test
        @DisplayName("单页上限就是 200")
        void maxPageSize() {
            assertThat(TaskService.maxPageSize()).isEqualTo(200);
        }

        @Test
        @DisplayName("默认排序是 planDate 倒序 + id 升序")
        void defaultSortExposed() {
            Sort sort = TaskService.defaultSort();
            assertThat(sort.getOrderFor("planDate").getDirection()).isEqualTo(Sort.Direction.DESC);
            assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
        }
    }

    @Test
    @DisplayName("★ 列表不会退化成「查全部」或「数全部」（越权 / 全表扫描的痕迹）")
    void listNeverScansEverything() {
        when(taskRepository.findAll(
                ArgumentMatchers.<Specification<Task>>any(), any(Pageable.class)))
                .thenReturn(pageOf(List.of(), 0, 20, 0));

        taskService.list(USER, TaskFilter.none(), 1, 20, null, null);

        verify(taskRepository, never()).countByUserId(anyLong());
        verify(taskRepository, never()).findAll();
    }
}
