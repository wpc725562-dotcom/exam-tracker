package com.wpc725562.examtracker.service;

import com.wpc725562.examtracker.common.BusinessException;
import com.wpc725562.examtracker.common.ErrorCode;
import com.wpc725562.examtracker.domain.Subject;
import com.wpc725562.examtracker.dto.SubjectDtos;
import com.wpc725562.examtracker.repository.CheckinRepository;
import com.wpc725562.examtracker.repository.SubjectRepository;
import com.wpc725562.examtracker.repository.TaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 科目增删改查。
 *
 * <p>重点是**删除保护**：删掉一个还有任务和打卡的科目，等于一键抹掉用户的历史。
 * 所以默认必须拒绝（409）并把数量告诉他，只有显式 {@code force=true} 才放行，
 * 而且删的顺序不能错（先打卡后任务，否则被外键挡住）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubjectService")
class SubjectServiceTest {

    @Mock
    private SubjectRepository subjectRepository;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private CheckinRepository checkinRepository;

    @InjectMocks
    private SubjectService subjectService;

    private static final Long USER = 1L;

    private static Subject subject(Long id, String name, String color, int target, int sort) {
        Subject s = new Subject(USER, name, color, target, sort);
        s.setId(id);
        return s;
    }

    @Nested
    @DisplayName("查询")
    class Queries {

        @Test
        @DisplayName("列表按 sortOrder 顺序映射成响应")
        void list() {
            when(subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(USER))
                    .thenReturn(List.of(subject(1L, "数学", "#4F46E5", 420, 0),
                            subject(2L, "英语", "#10B981", 300, 1)));

            List<SubjectDtos.SubjectResponse> result = subjectService.list(USER);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).name()).isEqualTo("数学");
            assertThat(result.get(1).name()).isEqualTo("英语");
        }

        @Test
        @DisplayName("★ 按 id 查别人的科目 -> 404（查询自带 userId 条件，越权读不到）")
        void getOthersSubject() {
            when(subjectRepository.findByIdAndUserId(99L, USER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> subjectService.get(USER, 99L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.NOT_FOUND));
        }

        @Test
        @DisplayName("requireSubject 供其它 Service 复用，越权同样 404")
        void requireSubject() {
            when(subjectRepository.findByIdAndUserId(5L, USER))
                    .thenReturn(Optional.of(subject(5L, "专业课", null, 600, 2)));

            assertThat(subjectService.requireSubject(USER, 5L).getName()).isEqualTo("专业课");
        }

        @Test
        @DisplayName("统计某科目下未完成任务数")
        void countPendingTasks() {
            when(taskRepository.countByUserIdAndSubjectIdAndStatus(
                    USER, 3L, com.wpc725562.examtracker.domain.TaskStatus.TODO)).thenReturn(4L);

            assertThat(subjectService.countPendingTasks(USER, 3L)).isEqualTo(4L);
        }
    }

    @Nested
    @DisplayName("新建")
    class Create {

        private SubjectDtos.CreateRequest req(String name, String color, Integer target, Integer sort) {
            return new SubjectDtos.CreateRequest(name, color, target, sort);
        }

        @Test
        @DisplayName("同名科目 -> 409 CONFLICT")
        void duplicateName() {
            when(subjectRepository.existsByUserIdAndName(USER, "数学")).thenReturn(true);

            assertThatThrownBy(() -> subjectService.create(USER, req("数学", null, 420, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("数学")
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.CONFLICT));
        }

        @Test
        @DisplayName("科目名首尾空格会被去掉")
        void nameIsTrimmed() {
            when(subjectRepository.existsByUserIdAndName(USER, "数学")).thenReturn(false);
            when(subjectRepository.save(any(Subject.class))).thenAnswer(inv -> {
                Subject s = inv.getArgument(0);
                s.setId(1L);
                return s;
            });

            subjectService.create(USER, req("  数学  ", null, 420, null));

            verify(subjectRepository).existsByUserIdAndName(USER, "数学");
        }

        @Test
        @DisplayName("不传排序值 -> 排到最后（用当前科目数，而不是全局计数器）")
        void defaultSortOrderIsCurrentCount() {
            when(subjectRepository.existsByUserIdAndName(anyLong(), any())).thenReturn(false);
            when(subjectRepository.countByUserId(USER)).thenReturn(3L);
            when(subjectRepository.save(any(Subject.class))).thenAnswer(inv -> {
                Subject s = inv.getArgument(0);
                s.setId(1L);
                return s;
            });

            subjectService.create(USER, req("语文", null, 300, null));

            ArgumentCaptor<Subject> saved = ArgumentCaptor.forClass(Subject.class);
            verify(subjectRepository).save(saved.capture());
            assertThat(saved.getValue().getSortOrder()).isEqualTo(3);
        }

        @Test
        @DisplayName("传了排序值 -> 用它")
        void explicitSortOrderWins() {
            when(subjectRepository.existsByUserIdAndName(anyLong(), any())).thenReturn(false);
            when(subjectRepository.save(any(Subject.class))).thenAnswer(inv -> {
                Subject s = inv.getArgument(0);
                s.setId(1L);
                return s;
            });

            subjectService.create(USER, req("语文", null, 300, 0));

            ArgumentCaptor<Subject> saved = ArgumentCaptor.forClass(Subject.class);
            verify(subjectRepository).save(saved.capture());
            assertThat(saved.getValue().getSortOrder()).isZero();
            verify(subjectRepository, never()).countByUserId(anyLong());
        }

        @Test
        @DisplayName("★ 颜色归一化：#abc -> #AABBCC，4f46e5 -> #4F46E5，空白 -> null")
        void colorIsNormalized() {
            when(subjectRepository.existsByUserIdAndName(anyLong(), any())).thenReturn(false);
            when(subjectRepository.save(any(Subject.class))).thenAnswer(inv -> {
                Subject s = inv.getArgument(0);
                s.setId(1L);
                return s;
            });

            assertThat(createdColor("数学", "#abc")).isEqualTo("#AABBCC");
            assertThat(createdColor("数学", "4f46e5")).isEqualTo("#4F46E5");
            assertThat(createdColor("数学", "  #4f46e5 ")).isEqualTo("#4F46E5");
            assertThat(createdColor("数学", "")).isNull();
            assertThat(createdColor("数学", null)).isNull();
        }

        private String createdColor(String name, String color) {
            SubjectDtos.SubjectResponse response =
                    subjectService.create(USER, req(name, color, 300, null));
            return response.color();
        }
    }

    @Nested
    @DisplayName("修改")
    class Update {

        @Test
        @DisplayName("★ 名字没变时不查重（否则改颜色会被自己挡住）")
        void sameNameSkipsDuplicateCheck() {
            Subject existing = subject(1L, "数学", "#4F46E5", 420, 0);
            when(subjectRepository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(existing));
            when(subjectRepository.save(any(Subject.class))).thenAnswer(inv -> inv.getArgument(0));

            subjectService.update(USER, 1L,
                    new SubjectDtos.UpdateRequest("数学", "#10B981", 500, null));

            verify(subjectRepository, never()).existsByUserIdAndName(anyLong(), any());
            assertThat(existing.getColor()).isEqualTo("#10B981");
            assertThat(existing.getTargetMinutesPerWeek()).isEqualTo(500);
        }

        @Test
        @DisplayName("改成别人已用的名字 -> 409")
        void renamedToExisting() {
            when(subjectRepository.findByIdAndUserId(1L, USER))
                    .thenReturn(Optional.of(subject(1L, "数学", null, 420, 0)));
            when(subjectRepository.existsByUserIdAndName(USER, "英语")).thenReturn(true);

            assertThatThrownBy(() -> subjectService.update(USER, 1L,
                    new SubjectDtos.UpdateRequest("英语", null, 420, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("英语");
        }

        @Test
        @DisplayName("排序值传 null -> 保留原值")
        void nullSortOrderKeepsOldValue() {
            Subject existing = subject(1L, "数学", null, 420, 7);
            when(subjectRepository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(existing));
            when(subjectRepository.save(any(Subject.class))).thenAnswer(inv -> inv.getArgument(0));

            subjectService.update(USER, 1L, new SubjectDtos.UpdateRequest("数学", null, 420, null));

            assertThat(existing.getSortOrder()).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("删除保护")
    class Delete {

        @Test
        @DisplayName("★ 科目下还有数据且没加 force -> 409，并把数量告诉用户")
        void refusesWhenHasData() {
            when(subjectRepository.findByIdAndUserId(1L, USER))
                    .thenReturn(Optional.of(subject(1L, "语文", null, 300, 0)));
            when(taskRepository.countByUserIdAndSubjectId(USER, 1L)).thenReturn(6L);
            when(checkinRepository.countByUserIdAndSubjectId(USER, 1L)).thenReturn(9L);

            assertThatThrownBy(() -> subjectService.delete(USER, 1L, false))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("语文")
                    .hasMessageContaining("6")
                    .hasMessageContaining("9")
                    .hasMessageContaining("force=true")
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.CONFLICT));

            // 拒绝之后一条都不能删
            verify(subjectRepository, never()).delete(any(Subject.class));
            verify(taskRepository, never()).deleteByUserIdAndSubjectId(anyLong(), anyLong());
            verify(checkinRepository, never()).deleteByUserIdAndSubjectId(anyLong(), anyLong());
        }

        @Test
        @DisplayName("★ force=true -> 先删打卡、再删任务、最后删科目（顺序错了会被外键挡住）")
        void forceDeletesInCorrectOrder() {
            when(subjectRepository.findByIdAndUserId(1L, USER))
                    .thenReturn(Optional.of(subject(1L, "语文", null, 300, 0)));
            when(taskRepository.countByUserIdAndSubjectId(USER, 1L)).thenReturn(2L);
            when(checkinRepository.countByUserIdAndSubjectId(USER, 1L)).thenReturn(3L);

            subjectService.delete(USER, 1L, true);

            InOrder order = inOrder(checkinRepository, taskRepository, subjectRepository);
            order.verify(checkinRepository).deleteByUserIdAndSubjectId(USER, 1L);
            order.verify(taskRepository).deleteByUserIdAndSubjectId(USER, 1L);
            order.verify(subjectRepository).delete(any(Subject.class));
        }

        @Test
        @DisplayName("空科目可以直接删（不触发批量删除）")
        void emptySubjectDeletesDirectly() {
            Subject existing = subject(1L, "语文", null, 300, 0);
            when(subjectRepository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(existing));
            when(taskRepository.countByUserIdAndSubjectId(USER, 1L)).thenReturn(0L);
            when(checkinRepository.countByUserIdAndSubjectId(USER, 1L)).thenReturn(0L);

            subjectService.delete(USER, 1L, false);

            verify(subjectRepository).delete(existing);
            verify(taskRepository, never()).deleteByUserIdAndSubjectId(anyLong(), anyLong());
            verify(checkinRepository, never()).deleteByUserIdAndSubjectId(anyLong(), anyLong());
        }

        @Test
        @DisplayName("★ 删别人的科目 -> 404（连数量都不该查）")
        void cannotDeleteOthersSubject() {
            when(subjectRepository.findByIdAndUserId(99L, USER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> subjectService.delete(USER, 99L, true))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.NOT_FOUND));

            verify(subjectRepository, never()).delete(any(Subject.class));
        }
    }
}
