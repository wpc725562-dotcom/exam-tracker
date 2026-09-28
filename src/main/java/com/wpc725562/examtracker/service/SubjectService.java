package com.wpc725562.examtracker.service;

import com.wpc725562.examtracker.common.BusinessException;
import com.wpc725562.examtracker.domain.Subject;
import com.wpc725562.examtracker.domain.TaskStatus;
import com.wpc725562.examtracker.dto.SubjectDtos;
import com.wpc725562.examtracker.repository.CheckinRepository;
import com.wpc725562.examtracker.repository.SubjectRepository;
import com.wpc725562.examtracker.repository.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 科目的增删改查。
 */
@Slf4j
@Service
public class SubjectService {

    private final SubjectRepository subjectRepository;
    private final TaskRepository taskRepository;
    private final CheckinRepository checkinRepository;

    public SubjectService(SubjectRepository subjectRepository,
                          TaskRepository taskRepository,
                          CheckinRepository checkinRepository) {
        this.subjectRepository = subjectRepository;
        this.taskRepository = taskRepository;
        this.checkinRepository = checkinRepository;
    }

    @Transactional(readOnly = true)
    public List<SubjectDtos.SubjectResponse> list(Long userId) {
        return subjectRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .map(SubjectService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SubjectDtos.SubjectResponse get(Long userId, Long subjectId) {
        return toResponse(requireSubject(userId, subjectId));
    }

    @Transactional
    public SubjectDtos.SubjectResponse create(Long userId, SubjectDtos.CreateRequest request) {
        String name = request.name().trim();

        if (subjectRepository.existsByUserIdAndName(userId, name)) {
            throw BusinessException.conflict("已存在同名科目：" + name);
        }

        // 不传排序值时排到最后 —— 新加的科目出现在列表末尾，符合预期；
        // 用「当前科目数」而不是某个全局计数器，避免删除后出现空洞。
        int sortOrder = request.sortOrder() != null
                ? request.sortOrder()
                : (int) subjectRepository.countByUserId(userId);

        Subject subject = new Subject(userId, name, normalizeColor(request.color()),
                request.targetMinutesPerWeek(), sortOrder);

        subject = subjectRepository.save(subject);
        log.info("新建科目: userId={} subjectId={} name={}", userId, subject.getId(), name);
        return toResponse(subject);
    }

    @Transactional
    public SubjectDtos.SubjectResponse update(Long userId, Long subjectId, SubjectDtos.UpdateRequest request) {
        Subject subject = requireSubject(userId, subjectId);
        String name = request.name().trim();

        // 改名时才查重；名字没变就别查了（否则改颜色也会被自己挡住）
        if (!name.equals(subject.getName()) && subjectRepository.existsByUserIdAndName(userId, name)) {
            throw BusinessException.conflict("已存在同名科目：" + name);
        }

        subject.setName(name);
        subject.setColor(normalizeColor(request.color()));
        subject.setTargetMinutesPerWeek(request.targetMinutesPerWeek());
        if (request.sortOrder() != null) {
            subject.setSortOrder(request.sortOrder());
        }

        return toResponse(subjectRepository.save(subject));
    }

    /**
     * 删除科目。
     *
     * <p><b>默认不允许删掉还有数据的科目</b> —— 直接级联删除任务和打卡记录是
     * 「一键毁掉历史」，用户点错一次就没了。所以默认返回 409 并把数量告诉他；
     * 确实要删时显式加 {@code force=true}。
     *
     * <p>这是一条通用原则：**破坏性操作要显式确认，而不是靠「他应该知道」。**
     */
    @Transactional
    public void delete(Long userId, Long subjectId, boolean force) {
        Subject subject = requireSubject(userId, subjectId);

        long taskCount = taskRepository.countByUserIdAndSubjectId(userId, subjectId);
        long checkinCount = checkinRepository.countByUserIdAndSubjectId(userId, subjectId);

        if ((taskCount > 0 || checkinCount > 0) && !force) {
            throw BusinessException.conflict(String.format(
                    "科目「%s」下还有 %d 个任务、%d 条打卡记录。"
                            + "确认要一并删除的话，请加 force=true 重试。",
                    subject.getName(), taskCount, checkinCount));
        }

        // 顺序不能反：先删打卡再删任务。checkin.task_id 指向 task，
        // 反过来删会被外键约束挡住。
        if (checkinCount > 0) {
            checkinRepository.deleteByUserIdAndSubjectId(userId, subjectId);
        }
        if (taskCount > 0) {
            taskRepository.deleteByUserIdAndSubjectId(userId, subjectId);
        }
        subjectRepository.delete(subject);

        log.warn("删除科目及其数据: userId={} subjectId={} name={} 任务={} 打卡={}",
                userId, subjectId, subject.getName(), taskCount, checkinCount);
    }

    /** 供其它 Service 复用：取科目实体并校验归属。 */
    @Transactional(readOnly = true)
    public Subject requireSubject(Long userId, Long subjectId) {
        return subjectRepository.findByIdAndUserId(subjectId, userId)
                .orElseThrow(() -> BusinessException.notFound("科目"));
    }

    /** 某科目下是否还有未完成任务 —— 统计接口和将来的业务规则会用到。 */
    @Transactional(readOnly = true)
    public long countPendingTasks(Long userId, Long subjectId) {
        return taskRepository.countByUserIdAndSubjectIdAndStatus(userId, subjectId, TaskStatus.TODO);
    }

    /**
     * 颜色归一化：允许不传，也允许只写 {@code #ABC} 这种三位简写。
     * 统一成大写、补全成六位，避免前端拿到 {@code #4f46e5} 和 {@code #4F46E5}
     * 两种写法又要写一次判断。
     */
    private static String normalizeColor(String color) {
        if (color == null || color.isBlank()) {
            return null;
        }
        String c = color.trim().toUpperCase();
        if (!c.startsWith("#")) {
            c = "#" + c;
        }
        if (c.length() == 4) {
            // #ABC -> #AABBCC
            StringBuilder sb = new StringBuilder("#");
            for (char ch : c.substring(1).toCharArray()) {
                sb.append(ch).append(ch);
            }
            c = sb.toString();
        }
        return c;
    }

    static SubjectDtos.SubjectResponse toResponse(Subject subject) {
        return new SubjectDtos.SubjectResponse(
                subject.getId(),
                subject.getName(),
                subject.getColor(),
                subject.getTargetMinutesPerWeek(),
                subject.getSortOrder());
    }
}
