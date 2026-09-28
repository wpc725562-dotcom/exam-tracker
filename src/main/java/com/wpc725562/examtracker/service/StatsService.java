package com.wpc725562.examtracker.service;

import com.wpc725562.examtracker.common.BusinessException;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计 —— 对应前端的「仪表盘」和「四科看板」。
 *
 * <p>所有聚合都推给数据库做（{@code count} / {@code sum} / {@code group by}），
 * 而不是「查出一堆实体在 Java 里循环加」。后者在数据量小的时候看不出区别，
 * 一旦记录上万，接口就会从 20ms 变成 3s —— 而且内存里会同时躺着上万条实体。
 */
@Slf4j
@Service
public class StatsService {

    /** 看板窗口天数的上限。防止 {@code ?days=100000} 这种请求把库拖垮。 */
    private static final int MAX_PERIOD_DAYS = 365;

    private static final int DEFAULT_PERIOD_DAYS = 7;

    private final TaskRepository taskRepository;
    private final CheckinRepository checkinRepository;
    private final SubjectRepository subjectRepository;
    private final UserRepository userRepository;

    public StatsService(TaskRepository taskRepository,
                        CheckinRepository checkinRepository,
                        SubjectRepository subjectRepository,
                        UserRepository userRepository) {
        this.taskRepository = taskRepository;
        this.checkinRepository = checkinRepository;
        this.subjectRepository = subjectRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public StatsDtos.OverviewResponse overview(Long userId) {
        LocalDate today = LocalDate.now();

        // ---- 今日 ----
        long todayTotal = taskRepository.countByUserIdAndPlanDate(userId, today);
        long todayDone = taskRepository.countByUserIdAndPlanDateAndStatus(userId, today, TaskStatus.DONE);
        long todaySkipped = taskRepository.countByUserIdAndPlanDateAndStatus(userId, today, TaskStatus.SKIPPED);
        long todayPending = taskRepository.countByUserIdAndPlanDateAndStatus(userId, today, TaskStatus.TODO);
        long todayPlanned = taskRepository.sumPlanMinutesByDate(userId, today);
        long todayActual = checkinRepository.sumMinutesByDate(userId, today);

        // ---- 连续打卡 ----
        List<LocalDate> checkinDates = checkinRepository.findDistinctCheckinDatesDesc(userId);
        long currentStreak = StreakCalculator.currentStreak(checkinDates, today);
        long longestStreak = StreakCalculator.longestStreak(checkinDates);

        // ---- 累计 ----
        long totalTasks = taskRepository.countByUserId(userId);
        long totalDone = taskRepository.countByUserIdAndStatus(userId, TaskStatus.DONE);
        long totalMinutes = checkinRepository.sumMinutesTotal(userId);

        // ---- 考试倒计时 ----
        User user = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("用户"));
        LocalDate examDate = user.getExamDate();

        return new StatsDtos.OverviewResponse(
                today,
                todayTotal, todayDone, todayPending, todaySkipped,
                rate(todayDone, todayTotal),
                todayPlanned, todayActual,
                currentStreak, longestStreak, checkinDates.size(),
                checkinDates.isEmpty() ? null : checkinDates.get(0),
                totalTasks, totalDone, totalMinutes,
                examDate,
                examDate == null ? null : ChronoUnit.DAYS.between(today, examDate));
    }

    @Transactional(readOnly = true)
    public StatsDtos.SubjectBoardResponse subjectBoard(Long userId, Integer periodDays) {
        int days = periodDays == null ? DEFAULT_PERIOD_DAYS : periodDays;
        if (days < 1) {
            throw BusinessException.invalidParam("统计天数至少为 1");
        }
        if (days > MAX_PERIOD_DAYS) {
            throw BusinessException.invalidParam("统计天数最多 " + MAX_PERIOD_DAYS + " 天，当前请求 " + days);
        }

        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(days - 1L);

        List<Subject> subjects = subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);

        // 四个聚合各查一次，然后在内存里按 subjectId 拼起来。
        // 相比「每个科目查四次」，这是 4 条 SQL 对 4N 条 SQL。
        Map<Long, Long> taskTotals = toMap(taskRepository.countGroupBySubject(userId));
        Map<Long, Long> taskDones =
                toMap(taskRepository.countGroupBySubjectAndStatus(userId, TaskStatus.DONE));
        Map<Long, Long> periodMinutes = new HashMap<>();
        checkinRepository.sumMinutesGroupBySubject(userId, from, to)
                .forEach(p -> periodMinutes.put(p.subjectId(), nz(p.minutes())));
        Map<Long, LocalDate> lastCheckins = new HashMap<>();
        checkinRepository.findLastCheckinDateGroupBySubject(userId)
                .forEach(p -> lastCheckins.put(p.subjectId(), p.lastCheckinDate()));

        long periodTotalMinutes = 0;
        long periodTargetMinutes = 0;
        List<SubjectDtos.SubjectProgressResponse> rows = new ArrayList<>(subjects.size());

        for (Subject subject : subjects) {
            long total = taskTotals.getOrDefault(subject.getId(), 0L);
            long done = taskDones.getOrDefault(subject.getId(), 0L);
            long pending = total - done;

            long minutes = periodMinutes.getOrDefault(subject.getId(), 0L);
            // 把「每周目标」折算到这个窗口：目标 420 分钟/周，窗口 7 天 → 420；
            // 窗口 3 天 → 420 × 3 / 7 = 180。用 double 算完再四舍五入，
            // 全程用 int 会因为整除把 3 天的目标算成 180 而不是 180（这个例子刚好），
            // 但 1 天时会算成 60 而不是 60 —— 真正出问题的是 5 天这种除不尽的。
            long target = Math.round(subject.getTargetMinutesPerWeek() * (days / 7.0));

            periodTotalMinutes += minutes;
            periodTargetMinutes += target;

            rows.add(new SubjectDtos.SubjectProgressResponse(
                    subject.getId(),
                    subject.getName(),
                    subject.getColor(),
                    subject.getTargetMinutesPerWeek(),
                    total,
                    done,
                    pending,
                    rate(done, total),
                    minutes,
                    target,
                    target == 0 ? 0.0 : round2((double) minutes / target),
                    lastCheckins.get(subject.getId())));
        }

        return new StatsDtos.SubjectBoardResponse(
                days, from, to,
                periodTotalMinutes, periodTargetMinutes,
                periodTargetMinutes == 0 ? 0.0 : round2((double) periodTotalMinutes / periodTargetMinutes),
                rows);
    }

    private static Map<Long, Long> toMap(List<SubjectCount> list) {
        Map<Long, Long> map = new HashMap<>();
        list.forEach(p -> map.put(p.subjectId(), nz(p.value())));
        return map;
    }

    /**
     * 完成率。
     *
     * <p>分母为 0 时返回 0 而不是抛异常 —— 「今天一个任务都没安排」是正常状态，
     * 前端显示「—」比报错好。但也不能返回 1.0（看着像全部完成）。
     */
    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : round2((double) numerator / denominator);
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    /** 保留两位小数。不做这步的话，JSON 里会出现 0.6666666666666666 这种值。 */
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
