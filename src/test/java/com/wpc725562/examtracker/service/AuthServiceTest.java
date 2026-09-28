package com.wpc725562.examtracker.service;

import com.wpc725562.examtracker.common.BusinessException;
import com.wpc725562.examtracker.common.ErrorCode;
import com.wpc725562.examtracker.domain.User;
import com.wpc725562.examtracker.dto.AuthDtos;
import com.wpc725562.examtracker.repository.UserRepository;
import com.wpc725562.examtracker.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 注册 / 登录 / 个人资料。
 *
 * <p>这里有两处**安全性质**必须钉住，它们都是「不测就一定会被后人改坏」的类型：
 * <ol>
 *   <li>登录失败时，「用户名不存在」和「密码错误」的提示必须**一模一样** ——
 *       否则攻击者能拿一批用户名快速筛出哪些是有效账号（账号枚举）；</li>
 *   <li>用户不存在时也要**真的跑一次 BCrypt 校验** ——
 *       否则「响应特别快」这件事本身就泄漏了「这个用户名不存在」。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthService authService;

    private static final String HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private static User existingUser(Long id, String username) {
        User user = new User(username, HASH, "备考中的我", LocalDate.of(2027, 6, 7));
        user.setId(id);
        return user;
    }

    /** 取异常消息；抛的不是 BusinessException 就直接失败。 */
    private static String messageOf(Executable executable) {
        try {
            executable.execute();
            throw new AssertionError("预期抛出 BusinessException，但方法正常返回了");
        } catch (BusinessException e) {
            return e.getMessage();
        } catch (Throwable t) {
            throw new AssertionError("预期 BusinessException，实际是 "
                    + t.getClass().getName() + ": " + t.getMessage(), t);
        }
    }

    private static ErrorCode codeOf(Executable executable) {
        try {
            executable.execute();
            throw new AssertionError("预期抛出 BusinessException，但方法正常返回了");
        } catch (BusinessException e) {
            return e.getErrorCode();
        } catch (Throwable t) {
            throw new AssertionError("预期 BusinessException，实际是 " + t.getClass().getName(), t);
        }
    }

    @Nested
    @DisplayName("注册")
    class Register {

        /** 主动查重和唯一索引兜底这两条路径必须对外表现完全一致。 */
        private static final String DUPLICATE_MESSAGE = "用户名已被占用：darling";

        @Test
        @DisplayName("用户名已存在 -> 409 CONFLICT，且不会写库")
        void duplicateUsername() {
            when(userRepository.existsByUsername("darling")).thenReturn(true);

            Executable call = () -> authService.register(
                    new AuthDtos.RegisterRequest("darling", "study2026", null, null));

            assertThat(codeOf(call)).isEqualTo(ErrorCode.CONFLICT);
            assertThat(messageOf(call)).isEqualTo(DUPLICATE_MESSAGE);
            verify(userRepository, never()).saveAndFlush(any(User.class));
        }

        @Test
        @DisplayName("密码只以 BCrypt 哈希入库，明文永不落库")
        void passwordIsHashed() {
            when(userRepository.existsByUsername("darling")).thenReturn(false);
            when(passwordEncoder.encode("study2026")).thenReturn(HASH);
            when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                u.setId(1L);
                return u;
            });

            authService.register(new AuthDtos.RegisterRequest("darling", "study2026", null, null));

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getPasswordHash()).isEqualTo(HASH);
            assertThat(saved.getValue().getPasswordHash()).isNotEqualTo("study2026");
        }

        @Test
        @DisplayName("★ 昵称不填 -> 默认与用户名相同（界面永远有东西可显示）")
        void blankNicknameFallsBackToUsername() {
            when(userRepository.existsByUsername("darling")).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn(HASH);
            when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                u.setId(1L);
                return u;
            });

            assertThat(authService.register(
                    new AuthDtos.RegisterRequest("darling", "study2026", null, null)).nickname())
                    .isEqualTo("darling");
            assertThat(authService.register(
                    new AuthDtos.RegisterRequest("darling", "study2026", "   ", null)).nickname())
                    .isEqualTo("darling");
            assertThat(authService.register(
                    new AuthDtos.RegisterRequest("darling", "study2026", " 备考中的我 ", null)).nickname())
                    .isEqualTo("备考中的我");
        }

        @Test
        @DisplayName("用户名首尾空格会被去掉")
        void usernameIsTrimmed() {
            when(userRepository.existsByUsername("darling")).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn(HASH);
            when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                u.setId(1L);
                return u;
            });

            authService.register(new AuthDtos.RegisterRequest("  darling  ", "study2026", null, null));

            verify(userRepository).existsByUsername("darling");
        }

        @Test
        @DisplayName("★ 并发下被唯一索引拦住 -> 与主动查重的报错完全一致（不泄漏用户名是否存在）")
        void concurrentDuplicateHitsUniqueIndex() {
            // 两个请求同时通过查重、再同时插入，是典型的 check-then-act 竞态。
            // 唯一索引是最后一道防线，但它的报错必须和主动查重**逐字一致** ——
            // 否则「错误信息不一样」本身就泄漏了「这个用户名存在」。
            when(userRepository.existsByUsername("darling")).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn(HASH);
            when(userRepository.saveAndFlush(any(User.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_app_user_username"));

            Executable call = () -> authService.register(
                    new AuthDtos.RegisterRequest("darling", "study2026", null, null));

            assertThat(codeOf(call)).isEqualTo(ErrorCode.CONFLICT);
            assertThat(messageOf(call))
                    .as("唯一索引兜底的报错必须与主动查重逐字相同")
                    .isEqualTo(DUPLICATE_MESSAGE);
        }

        @Test
        @DisplayName("返回体里不含密码哈希")
        void responseNeverExposesHash() {
            when(userRepository.existsByUsername("darling")).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn(HASH);
            when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                u.setId(1L);
                return u;
            });

            AuthDtos.UserInfo info = authService.register(
                    new AuthDtos.RegisterRequest("darling", "study2026", null, LocalDate.of(2027, 6, 7)));

            assertThat(info.toString()).doesNotContain(HASH);
            assertThat(info.username()).isEqualTo("darling");
            assertThat(info.id()).isEqualTo(1L);
        }

        @Test
        @DisplayName("考试日期为空 -> daysUntilExam 为 null（不是 0）")
        void nullExamDateYieldsNullCountdown() {
            when(userRepository.existsByUsername(anyString())).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn(HASH);
            when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                u.setId(1L);
                return u;
            });

            AuthDtos.UserInfo info = authService.register(
                    new AuthDtos.RegisterRequest("darling", "study2026", null, null));

            assertThat(info.examDate()).isNull();
            assertThat(info.daysUntilExam()).isNull();
        }

        @Test
        @DisplayName("考试日期已过 -> 倒计时是负数（保留负数才能区分「今天」和「已考完」）")
        void pastExamDateYieldsNegativeCountdown() {
            when(userRepository.existsByUsername(anyString())).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn(HASH);
            when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                u.setId(1L);
                return u;
            });

            AuthDtos.UserInfo info = authService.register(new AuthDtos.RegisterRequest(
                    "darling", "study2026", null, LocalDate.now().minusDays(3)));

            assertThat(info.daysUntilExam()).isEqualTo(-3L);
        }
    }

    @Nested
    @DisplayName("登录")
    class Login {

        @Test
        @DisplayName("★ 用户名不存在与密码错误返回完全相同的提示（防账号枚举）")
        void sameMessageForBothFailures() {
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
            when(userRepository.findByUsername("darling")).thenReturn(Optional.of(existingUser(1L, "darling")));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            String notFound = messageOf(() -> authService.login(new AuthDtos.LoginRequest("ghost", "x")));
            String wrongPassword = messageOf(() -> authService.login(new AuthDtos.LoginRequest("darling", "x")));

            assertThat(notFound).isEqualTo(wrongPassword).isEqualTo("用户名或密码错误");
        }

        @Test
        @DisplayName("★ 用户不存在时也会真的跑一次 BCrypt 校验（抹掉时间侧信道）")
        void missingUserStillHashesPassword() {
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            assertThat(codeOf(() -> authService.login(new AuthDtos.LoginRequest("ghost", "x"))))
                    .isEqualTo(ErrorCode.UNAUTHORIZED);

            ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
            verify(passwordEncoder).matches(eq("x"), hash.capture());

            // 断言「确实拿了一个真的 BCrypt 哈希去比对」，而不是短路成 no-op。
            // 否则这个用户不存在时耗时接近 0，「快 = 不存在」就成了新的枚举通道。
            assertThat(hash.getValue()).startsWith("$2a$").hasSize(60);
        }

        @Test
        @DisplayName("登录失败时不会签发 token")
        void noTokenOnFailure() {
            when(userRepository.findByUsername("darling")).thenReturn(Optional.of(existingUser(1L, "darling")));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            assertThatThrownBy(() -> authService.login(new AuthDtos.LoginRequest("darling", "wrong")))
                    .isInstanceOf(BusinessException.class);

            verifyNoInteractions(jwtService);
        }

        @Test
        @DisplayName("登录成功 -> 返回 token / Bearer / 有效期 / 用户信息")
        void success() {
            when(userRepository.findByUsername("darling")).thenReturn(Optional.of(existingUser(1L, "darling")));
            when(passwordEncoder.matches("study2026", HASH)).thenReturn(true);
            when(jwtService.generateToken(1L, "darling")).thenReturn("header.payload.sig");
            when(jwtService.getExpiresInSeconds()).thenReturn(43_200L);

            AuthDtos.TokenResponse response =
                    authService.login(new AuthDtos.LoginRequest("darling", "study2026"));

            assertThat(response.token()).isEqualTo("header.payload.sig");
            assertThat(response.tokenType()).isEqualTo("Bearer");
            assertThat(response.expiresIn()).isEqualTo(43_200L);
            assertThat(response.user().id()).isEqualTo(1L);
            assertThat(response.user().username()).isEqualTo("darling");
        }

        @Test
        @DisplayName("登录时用户名首尾空格会被去掉")
        void usernameIsTrimmed() {
            when(userRepository.findByUsername("darling")).thenReturn(Optional.of(existingUser(1L, "darling")));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
            when(jwtService.generateToken(1L, "darling")).thenReturn("t");
            when(jwtService.getExpiresInSeconds()).thenReturn(1L);

            authService.login(new AuthDtos.LoginRequest("  darling ", "study2026"));

            verify(userRepository).findByUsername("darling");
        }
    }

    @Nested
    @DisplayName("个人资料")
    class Profile {

        @Test
        @DisplayName("用户不存在 -> 401（不是 404：不确认「这个 id 存在过」）")
        void missingUserIsUnauthorized() {
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            assertThat(codeOf(() -> authService.getProfile(99L))).isEqualTo(ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("昵称传空白 -> 保持原值不动")
        void blankNicknameKeepsOldValue() {
            User user = existingUser(1L, "darling");
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            authService.updateProfile(1L, new AuthDtos.UpdateProfileRequest("   ", user.getExamDate()));

            assertThat(user.getNickname()).isEqualTo("备考中的我");
        }

        @Test
        @DisplayName("昵称传值 -> 覆盖并去掉首尾空格")
        void nicknameIsUpdatedAndTrimmed() {
            User user = existingUser(1L, "darling");
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            authService.updateProfile(1L, new AuthDtos.UpdateProfileRequest(" 冲刺阶段 ", user.getExamDate()));

            assertThat(user.getNickname()).isEqualTo("冲刺阶段");
        }

        @Test
        @DisplayName("★ 考试日期传 null -> 清空（「传了就是设置，不传就是不动」）")
        void nullExamDateClearsIt() {
            User user = existingUser(1L, "darling");
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            AuthDtos.UserInfo info =
                    authService.updateProfile(1L, new AuthDtos.UpdateProfileRequest("darling", null));

            assertThat(info.examDate()).isNull();
            assertThat(info.daysUntilExam()).isNull();
        }
    }
}
