package com.wpc725562.examtracker.service;

import com.wpc725562.examtracker.common.BusinessException;
import com.wpc725562.examtracker.common.PageResult;
import com.wpc725562.examtracker.common.SortResolver;
import com.wpc725562.examtracker.domain.Subject;
import com.wpc725562.examtracker.domain.Task;
import com.wpc725562.examtracker.domain.TaskStatus;
import com.wpc725562.examtracker.dto.TaskDtos;
import com.wpc725562.examtracker.dto.TaskFilter;
import com.wpc725562.examtracker.repository.SubjectRepository;
import com.wpc725562.examtracker.repository.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 任务的增删改查。
 */
@Slf4j
@Service
public class TaskService {

    /**
     * 默认排序：先按计划日期倒序（最近的在最上面），再按优先级（HIGH 在前），
     * 最后用 id 保证稳定。
     *
     * <p>优先级是枚举，排序用的是**字符串**顺序，所以 {@code HIGH < LOW < MEDIUM} ——
     * 这不是我们想要的语义。真正的排序规则在下面 {@link #DEFAULT_SORT} 里说明，
     * 这里刻意不把 priority 放进默认排序，避免给人「高优先级一定排前面」的错觉。
     */
    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Direction.DESC, "planDate")
                    .and(Sort.by(Sort.Direction.ASC, "id"));

    /** 单页最大条数。挡住 {@code ?size=100000} 这种把库拖垮的请求。 */
    private static final int MAX_PAGE_SIZE = 200;

    private final TaskRepository taskRepository;
    private final SubjectRepository subjectRepository;
    private final SubjectService subjectService;

    public TaskService(TaskRepository taskRepository,
                       SubjectRepository subjectRepository,
                       SubjectService subjectService) {
        this.taskRepository = taskRepository;
        this.subjectRepository = subjectRepository;
        this.subjectService = subjectService;
    }

    @Transactional(readOnly = true)
    public PageResult<TaskDtos.TaskResponse> list(Long userId, TaskFilter filter, int page, int size,
                                                  String sortBy, String direction) {
        if (size > MAX_PAGE_SIZE) {
            throw BusinessException.invalidParam("每页最多 " + MAX_PAGE_SIZE + " 条，当前请求 " + size);
        }

        Specification<Task> spec = TaskSpecifications.ownedBy(userId)
                .and(TaskSpecifications.planDateFrom(filter.from()))
                .and(TaskSpecifications.planDateTo(filter.to()))
                .and(TaskSpecifications.subjectId(filter.subjectId()))
                .and(TaskSpecifications.status(filter.status()))
                .and(TaskSpecifications.priority(filter.priority()))
                .and(TaskSpecifications.titleContains(filter.keyword()));

        Sort sort = SortResolver.resolve(sortBy, direction, DEFAULT_SORT);

        // 页码对外从 1 开始，Spring Data 内部从 0 开始 —— 转换只在这一处做，
        // 免得每个调用点都要记得减 1。
        Page<Task> result = taskRepository.findAll(spec, PageRequest.of(Math.max(page, 1) - 1, size, sort));

        // 科目名一次查出来做成 Map，避免「每条任务查一次科目」的 N+1
        Map<Long, String> subjectNames = subjectNameMap(userId);

        return PageResult.of(result, task -> toResponse(task, subjectNames));
    }

    @Transactional(readOnly = true)
    public TaskDtos.TaskResponse get(Long userId, Long taskId) {
        Task task = requireTask(userId, taskId);
        return toResponse(task, subjectNameMap(userId));
    }

    @Transactional
    public TaskDtos.TaskResponse create(Long userId, TaskDtos.CreateRequest request) {
        // 先确认科目存在且属于当前用户 —— 否则就是「把任务挂到别人科目上」
        Subject subject = subjectService.requireSubject(userId, request.subjectId());

        Task task = new Task(userId, subject.getId(), request.title().trim(), request.planDate(),
                request.planMinutes(), request.priority(), trimToNull(request.note()));

        task = taskRepository.save(task);
        log.info("新建任务: userId={} taskId={} subject={} planDate={}",
                userId, task.getId(), subject.getName(), task.getPlanDate());

        return toResponse(task, Map.of(subject.getId(), subject.getName()));
    }

    @Transactional
    public TaskDtos.TaskResponse update(Long userId, Long taskId, TaskDtos.UpdateRequest request) {
        Task task = requireTask(userId, taskId);
        Subject subject = subjectService.requireSubject(userId, request.subjectId());

        task.setSubjectId(subject.getId());
        task.setTitle(request.title().trim());
        task.setPlanDate(request.planDate());
        task.setPlanMinutes(request.planMinutes());
        if (request.priority() != null) {
            task.setPriority(request.priority());
        }
        task.setNote(trimToNull(request.note()));

        task = taskRepository.save(task);
        return toResponse(task, Map.of(subject.getId(), subject.getName()));
    }

    /**
     * 变更状态。
     *
     * <p>「完成时间」由 {@link Task#changeStatus} 统一维护，
     * 不在这里单独 set —— 状态和完成时间必须永远一致，
     * 把这条不变式收在实体方法里，调用方就没有写错的机会。
     */
    @Transactional
    public TaskDtos.TaskResponse changeStatus(Long userId, Long taskId, TaskDtos.StatusRequest request) {
        Task task = requireTask(userId, taskId);
        TaskStatus previous = task.getStatus();

        task.changeStatus(request.status(), LocalDateTime.now());
        task = taskRepository.save(task);

        if (previous != request.status()) {
            log.info("任务状态变更: userId={} taskId={} {} -> {}",
                    userId, taskId, previous, request.status());
        }
        return toResponse(task, subjectNameMap(userId));
    }

    /**
     * 删除任务。
     *
     * <p><b>不会删掉它的打卡记录。</b>数据库外键是 {@code ON DELETE SET NULL}：
     * 任务没了，但「那天确实学了 90 分钟」这个事实还在，只是不再关联到具体任务。
     * 如果把打卡一起删掉，用户的累计时长和连续天数会莫名其妙地减少 ——
     * 那是把「整理待办」和「抹掉历史」混为一谈了。
     */
    @Transactional
    public void delete(Long userId, Long taskId) {
        Task task = requireTask(userId, taskId);
        taskRepository.delete(task);
        log.info("删除任务: userId={} taskId={}", userId, taskId);
    }

    /** 供其它 Service 复用。 */
    @Transactional(readOnly = true)
    public Task requireTask(Long userId, Long taskId) {
        return taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> BusinessException.notFound("任务"));
    }

    /**
     * 当前用户的「科目 id → 科目名」映射。
     *
     * <p>一次查询换掉 N 次查询。列表接口里每条任务都要显示科目名，
     * 逐条去查就是典型的 N+1 —— 20 条数据变成 21 条 SQL，
     * 数据量一大就非常明显。
     */
    @Transactional(readOnly = true)
    public Map<Long, String> subjectNameMap(Long userId) {
        return subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .collect(Collectors.toMap(Subject::getId, Subject::getName, (a, b) -> a));
    }

    static TaskDtos.TaskResponse toResponse(Task task, Map<Long, String> subjectNames) {
        return new TaskDtos.TaskResponse(
                task.getId(),
                task.getSubjectId(),
                // 科目被删掉时兜底，而不是返回 null 让前端崩
                subjectNames.getOrDefault(task.getSubjectId(), "（科目已删除）"),
                task.getTitle(),
                task.getPlanDate(),
                task.getPlanMinutes(),
                task.getPriority(),
                task.getStatus(),
                task.getNote(),
                task.getCompletedAt(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }

    /** 把「只有空白字符」的备注统一成 null，免得库里同时存在 {@code ""} 和 {@code null} 两种「没填」。 */
    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 暴露给测试与统计服务：默认排序。 */
    public static Sort defaultSort() {
        return DEFAULT_SORT;
    }

    /** 暴露给测试：单页上限。 */
    public static int maxPageSize() {
        return MAX_PAGE_SIZE;
    }
}
