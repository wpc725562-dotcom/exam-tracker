package com.wpc725562.examtracker.service;

import com.wpc725562.examtracker.common.BusinessException;
import com.wpc725562.examtracker.common.ErrorCode;
import com.wpc725562.examtracker.domain.User;
import com.wpc725562.examtracker.dto.AuthDtos;
import com.wpc725562.examtracker.repository.UserRepository;
import com.wpc725562.examtracker.security.JwtService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 注册、登录、个人资料。
 */
@Slf4j
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * 注册。
     *
     * <p>「先查重再插入」和「靠数据库唯一索引拦」两条都做了，而且**不能只做前者**：
     * 两个请求同时通过查重、再同时插入，是典型的 check-then-act 竞态。
     * 唯一索引是最后一道、也是唯一真正可靠的防线。
     */
    @Transactional
    public AuthDtos.UserInfo register(AuthDtos.RegisterRequest request) {
        String username = request.username().trim();

        if (userRepository.existsByUsername(username)) {
            throw BusinessException.conflict("用户名已被占用：" + username);
        }

        User user = new User(
                username,
                passwordEncoder.encode(request.password()),
                // 昵称不填就与用户名相同 —— 保证界面上永远有东西可显示，不用到处判空
                (request.nickname() == null || request.nickname().isBlank())
                        ? username : request.nickname().trim(),
                request.examDate());

        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // 并发下被唯一索引拦住的路径：对外表现要和上面的主动查重完全一致，
            // 否则「有没有这个用户名」会通过错误信息的差异泄漏出去。
            log.warn("注册时命中唯一索引，疑似并发重复提交: {}", username);
            throw BusinessException.conflict("用户名已被占用：" + username);
        }

        log.info("新用户注册成功: id={} username={}", user.getId(), user.getUsername());
        return toUserInfo(user);
    }

    /**
     * 登录。
     *
     * <p><b>「用户名不存在」和「密码错误」返回完全相同的提示。</b>
     * 这不是偷懒，是刻意的：如果两者的报错不一样，攻击者就能拿一堆用户名
     * 快速筛出哪些是有效账号（用户名枚举），再去针对性爆破。
     */
    @Transactional(readOnly = true)
    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest request) {
        String username = request.username().trim();

        User user = userRepository.findByUsername(username).orElse(null);

        // 用户不存在时也走一次密码校验：让两条路径的耗时接近，
        // 否则「响应快 = 用户不存在」这个时间差本身就能被用来枚举账号。
        boolean passwordMatches = user != null
                && passwordEncoder.matches(request.password(), user.getPasswordHash());
        if (user == null) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
        }

        if (!passwordMatches) {
            log.warn("登录失败: username={}", username);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        String token = jwtService.generateToken(user.getId(), user.getUsername());
        log.info("登录成功: id={} username={}", user.getId(), user.getUsername());

        return new AuthDtos.TokenResponse(
                token,
                "Bearer",
                jwtService.getExpiresInSeconds(),
                toUserInfo(user));
    }

    @Transactional(readOnly = true)
    public AuthDtos.UserInfo getProfile(Long userId) {
        return toUserInfo(requireUser(userId));
    }

    @Transactional
    public AuthDtos.UserInfo updateProfile(Long userId, AuthDtos.UpdateProfileRequest request) {
        User user = requireUser(userId);
        if (request.nickname() != null && !request.nickname().isBlank()) {
            user.setNickname(request.nickname().trim());
        }
        // 允许显式传 null 来「清除」考试日期：区分「没传这个字段」和「传了 null」
        // 在 JSON 里做不到，所以这里约定「传了就是设置，不传就是不动」，
        // 清除用 examDate = null 显式表达 —— 见 Controller 上的说明。
        user.setExamDate(request.examDate());
        return toUserInfo(userRepository.save(user));
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在或已被删除"));
    }

    private AuthDtos.UserInfo toUserInfo(User user) {
        LocalDate examDate = user.getExamDate();
        return new AuthDtos.UserInfo(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                examDate,
                // 用 ChronoUnit 而不是手算毫秒：跨月、跨年、闰年都由它处理，
                // 而且它算的是「日历天数」而不是「24 小时数」，符合直觉。
                examDate == null ? null : ChronoUnit.DAYS.between(LocalDate.now(), examDate));
    }

    /**
     * 一个固定的 BCrypt 哈希，用于「用户不存在时也跑一次校验」。
     *
     * <p>值本身没有意义（对应一个随机密码），只是为了让耗时可比。
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
}
