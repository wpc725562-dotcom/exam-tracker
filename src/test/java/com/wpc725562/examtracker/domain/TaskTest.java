package com.wpc725562.examtracker.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Task} 实体自身的规则。
 *
 * <p>这里测的是**不变式**：「completedAt 有值」和「status == DONE」必须永远一致。
 * 把这条规则收在实体方法 {@code changeStatus} 里，而不是让 Service 分别 set 两个字段 ——
 * 后者迟早有人只改一个，然后数据库里就出现了「未完成但有完成时间」的记录。
 */
@DisplayName("Task —— 状态与完成时间的不变式")
class TaskTest {

    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 9, 28);

    private static Task newTask(Priority priority) {
        return new Task(1L, 10L, "做一套真题", PLAN_DATE, 90, priority, "错题整理到错题本");
    }

    @Test
    @DisplayName("新建任务默认是 TODO，且没有完成时间")
    void defaultsOnCreate() {
        Task task = newTask(Priority.HIGH);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(task.getCompletedAt()).isNull();
        assertThat(task.getPriority()).isEqualTo(Priority.HIGH);
        assertThat(task.getPlanDate()).isEqualTo(PLAN_DATE);
        assertThat(task.getPlanMinutes()).isEqualTo(90);
    }

    @Test
    @DisplayName("★ 优先级传 null -> 兜底成 MEDIUM（不是 null 进库）")
    void nullPriorityFallsBackToMedium() {
        // priority 列是 not null，如果这里不兜底，插入时会直接报
        // DataIntegrityViolationException —— 一个本该在构造时就消化掉的问题。
        assertThat(newTask(null).getPriority()).isEqualTo(Priority.MEDIUM);
    }

    @Test
    @DisplayName("标记为 DONE -> 写入完成时间")
    void doneSetsCompletedAt() {
        Task task = newTask(Priority.MEDIUM);
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 21, 30);

        task.changeStatus(TaskStatus.DONE, now);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getCompletedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("★ 从 DONE 改回 TODO -> 清空完成时间（状态与时间始终一致）")
    void backToTodoClearsCompletedAt() {
        Task task = newTask(Priority.MEDIUM);
        task.changeStatus(TaskStatus.DONE, LocalDateTime.now());
        assertThat(task.getCompletedAt()).isNotNull();

        task.changeStatus(TaskStatus.TODO, LocalDateTime.now());

        assertThat(task.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(task.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("改成 SKIPPED -> 也清空完成时间（跳过不等于完成）")
    void skippedClearsCompletedAt() {
        Task task = newTask(Priority.MEDIUM);
        task.changeStatus(TaskStatus.DONE, LocalDateTime.now());

        task.changeStatus(TaskStatus.SKIPPED, LocalDateTime.now());

        assertThat(task.getStatus()).isEqualTo(TaskStatus.SKIPPED);
        assertThat(task.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("重复标记 DONE -> 完成时间更新为最新一次")
    void repeatedDoneUpdatesTimestamp() {
        Task task = newTask(Priority.MEDIUM);
        LocalDateTime first = LocalDateTime.of(2026, 9, 28, 9, 0);
        LocalDateTime second = LocalDateTime.of(2026, 9, 28, 22, 0);

        task.changeStatus(TaskStatus.DONE, first);
        task.changeStatus(TaskStatus.DONE, second);

        assertThat(task.getCompletedAt()).isEqualTo(second);
    }

    @Test
    @DisplayName("状态枚举只有 TODO / DONE / SKIPPED（没有「进行中」这种半吊子状态）")
    void statusEnumIsMinimal() {
        assertThat(TaskStatus.values())
                .containsExactly(TaskStatus.TODO, TaskStatus.DONE, TaskStatus.SKIPPED);
    }
}
