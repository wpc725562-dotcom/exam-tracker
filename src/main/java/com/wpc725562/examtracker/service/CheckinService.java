package com.wpc725562.examtracker.service;

import com.wpc725562.examtracker.common.BusinessException;
import com.wpc725562.examtracker.common.PageResult;
import com.wpc725562.examtracker.domain.Checkin;
import com.wpc725562.examtracker.domain.Subject;
import com.wpc725562.examtracker.domain.Task;
import com.wpc725562.examtracker.dto.CheckinDtos;
import com.wpc725562.examtracker.repository.CheckinRepository;
import com.wpc725562.examtracker.repository.SubjectRepository;
import com.wpc725562.examtracker.repository.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 打卡记录的增删查。
 */
@Slf4j
@Service
public class CheckinService {

    private static final int MAX_PAGE_SIZE = 200;

    /** 不传时间范围时默认回溯的天数 —— 防止「打开页面就拉全表」。 */
    private static final int DEFAULT_RANGE_DAYS = 30;

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "checkinDate")
            .and(Sort.by(Sort.Direction.DESC, "id"));

    private final CheckinRepository checkinRepository;
    private final SubjectRepository subjectRepository;
    private final TaskRepository taskRepository;
    private final SubjectService subjectService;

    public CheckinService(CheckinRepository checkinRepository,
                          SubjectRepository subjectRepository,
                          TaskRepository taskRepository,
                          SubjectService subjectService) {
        this.checkinRepository = checkinRepository;
        this.subjectRepository = subjectRepository;
        this.taskRepository = taskRepository;
        this.subjectService = subjectService;
    }

    /**
     * 新增打卡。
     *
     * <p><b>科目从任务推导，而不是让调用方同时传两个。</b>
     * 如果允许「任务的科目是数学、打卡的科目是英语」，就产生了自相矛盾的数据，
     * 之后按科目统计时两边对不上。这里把非法状态**在结构上排除掉**：
     * 传了 {@code taskId} 就以任务为准，{@code subjectId} 被忽略。
     */
    @Transactional
    public CheckinDtos.CheckinResponse create(Long userId, CheckinDtos.CreateRequest request) {

        Long subjectId;
        Task task = null;

        if (request.taskId() != null) {
            // 必须是本人的任务。找不到时统一报「任务不存在」，
            // 不区分「真的没有」和「是别人的」—— 否则可以通过错误信息的差异
            // 探测出别人有哪些任务 id。
            task = taskRepository.findByIdAndUserId(request.taskId(), userId)
                    .orElseThrow(() -> BusinessException.notFound("任务"));
            subjectId = task.getSubjectId();
        } else {
            if (request.subjectId() == null) {
                throw BusinessException.invalidParam("subjectId 和 taskId 至少要填一个");
            }
            subjectId = subjectService.requireSubject(userId, request.subjectId()).getId();
        }

        // 日期不填就默认今天。在服务端算而不是让前端传：
        // 客户端时钟不准或跨时区时，会把记录写到昨天或明天去。
        LocalDate checkinDate = request.checkinDate() != null ? request.checkinDate() : LocalDate.now();
        if (checkinDate.isAfter(LocalDate.now())) {
            throw BusinessException.invalidParam("打卡日期不能是未来：" + checkinDate);
        }

        Checkin checkin = new Checkin(userId, subjectId, task == null ? null : task.getId(),
                checkinDate, request.actualMinutes(), trimToNull(request.note()));

        checkin = checkinRepository.save(checkin);
        log.info("新增打卡: userId={} checkinId={} subjectId={} date={} minutes={}",
                userId, checkin.getId(), subjectId, checkinDate, request.actualMinutes());

        return toResponse(checkin, subjectNames(userId), taskTitles(userId, List.of(checkin)));
    }

    @Transactional(readOnly = true)
    public PageResult<CheckinDtos.CheckinResponse> list(Long userId, LocalDate from, LocalDate to,
                                                        Long subjectId, int page, int size) {
        if (size > MAX_PAGE_SIZE) {
            throw BusinessException.invalidParam("每页最多 " + MAX_PAGE_SIZE + " 条，当前请求 " + size);
        }

        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);
        if (start.isAfter(end)) {
            throw BusinessException.invalidParam("开始日期不能晚于结束日期");
        }

        Pageable pageable = PageRequest.of(Math.max(page, 1) - 1, size, DEFAULT_SORT);

        Page<Checkin> result = subjectId == null
                ? checkinRepository.findByUserIdAndCheckinDateBetween(userId, start, end, pageable)
                : checkinRepository.findByUserIdAndSubjectIdAndCheckinDateBetween(
                        userId, subjectId, start, end, pageable);

        // 只为本页出现的任务查标题，而不是把用户所有任务都拉出来 ——
        // 任务可能有几千条，打卡一页只有 20 条。
        Map<Long, String> subjectNames = subjectNames(userId);
        Map<Long, String> taskTitles = taskTitles(userId, result.getContent());

        return PageResult.of(result, c -> toResponse(c, subjectNames, taskTitles));
    }

    /** 日历打卡视图：只返回「哪天总共多少分钟」，不返回明细。 */
    @Transactional(readOnly = true)
    public List<CheckinDtos.DailyMinutes> dailyMinutes(Long userId, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);
        if (start.isAfter(end)) {
            throw BusinessException.invalidParam("开始日期不能晚于结束日期");
        }
        return checkinRepository.sumMinutesGroupByDate(userId, start, end).stream()
                .map(p -> new CheckinDtos.DailyMinutes(p.date(), p.minutes() == null ? 0L : p.minutes()))
                .toList();
    }

    @Transactional
    public void delete(Long userId, Long checkinId) {
        Checkin checkin = checkinRepository.findByIdAndUserId(checkinId, userId)
                .orElseThrow(() -> BusinessException.notFound("打卡记录"));
        checkinRepository.delete(checkin);
        log.info("删除打卡: userId={} checkinId={}", userId, checkinId);
    }

    /** 科目 id → 名称。 */
    private Map<Long, String> subjectNames(Long userId) {
        return subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .collect(Collectors.toMap(Subject::getId, Subject::getName, (a, b) -> a));
    }

    /**
     * 任务 id → 标题，只为传入的这批打卡记录里出现的任务查。
     *
     * <p>而且**按 userId 过滤** —— 即使数据里残留了不属于当前用户的任务 id
     * （理论上被外键挡住，但防御一层），也不会把别人的任务标题读出来。
     */
    private Map<Long, String> taskTitles(Long userId, Collection<Checkin> checkins) {
        Set<Long> taskIds = checkins.stream()
                .map(Checkin::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return taskRepository.findAllById(taskIds).stream()
                .filter(t -> t.getUserId().equals(userId))
                .collect(Collectors.toMap(Task::getId, Task::getTitle, (a, b) -> a));
    }

    private static CheckinDtos.CheckinResponse toResponse(Checkin checkin,
                                                          Map<Long, String> subjectNames,
                                                          Map<Long, String> taskTitles) {
        return new CheckinDtos.CheckinResponse(
                checkin.getId(),
                checkin.getSubjectId(),
                subjectNames.getOrDefault(checkin.getSubjectId(), "（科目已删除）"),
                checkin.getTaskId(),
                checkin.getTaskId() == null ? null : taskTitles.get(checkin.getTaskId()),
                checkin.getCheckinDate(),
                checkin.getActualMinutes(),
                checkin.getNote(),
                checkin.getCreatedAt());
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
