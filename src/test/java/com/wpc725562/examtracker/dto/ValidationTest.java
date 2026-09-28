package com.wpc725562.examtracker.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DTO 上的校验注解。
 *
 * <p>直接用 jakarta 的 {@link Validator} 跑，不经过 Spring —— 这样测的是
 * **注解本身写得对不对**（边界值、正则、长度），与 MVC 的装配无关，跑得也快。
 * 「校验失败时 HTTP 长什么样」由 {@code GlobalExceptionHandlerTest} 和端到端脚本覆盖。
 *
 * <p>为什么值得逐个钉边界值：{@code @Min(1)} 写成 {@code @Min(0)}、
 * {@code @Size(max=200)} 写成 2000 —— 这类错误不会报错，只会让脏数据进库。
 */
@DisplayName("DTO 校验规则")
class ValidationTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static <T> Set<ConstraintViolation<T>> violations(T bean) {
        return VALIDATOR.validate(bean);
    }

    private static <T> Set<String> violatedFields(T bean) {
        return violations(bean).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    @Nested
    @DisplayName("注册请求")
    class Register {

        @Test
        @DisplayName("合法请求没有违规")
        void valid() {
            assertThat(violations(new AuthDtos.RegisterRequest(
                    "darling", "study2026", "备考中的我", LocalDate.of(2027, 6, 7)))).isEmpty();
        }

        @Test
        @DisplayName("昵称和考试日期可以不填")
        void optionalFields() {
            assertThat(violations(new AuthDtos.RegisterRequest(
                    "darling", "study2026", null, null))).isEmpty();
        }

        @Test
        @DisplayName("用户名：不能为空、至少 3 位、只能是字母数字下划线")
        void usernameRules() {
            assertThat(violatedFields(reg(null, "study2026"))).contains("username");
            assertThat(violatedFields(reg("", "study2026"))).contains("username");
            assertThat(violatedFields(reg("ab", "study2026"))).contains("username");
            assertThat(violatedFields(reg("有中文", "study2026"))).contains("username");
            assertThat(violatedFields(reg("darling-01", "study2026"))).contains("username");
            assertThat(violatedFields(reg("darling_01", "study2026"))).isEmpty();
        }

        @Test
        @DisplayName("用户名最长 50 位")
        void usernameMaxLength() {
            assertThat(violatedFields(reg("a".repeat(50), "study2026"))).isEmpty();
            assertThat(violatedFields(reg("a".repeat(51), "study2026"))).contains("username");
        }

        @Test
        @DisplayName("密码：6-64 位")
        void passwordRules() {
            assertThat(violatedFields(reg("darling", "12345"))).contains("password");
            assertThat(violatedFields(reg("darling", "123456"))).isEmpty();
            assertThat(violatedFields(reg("darling", "a".repeat(64)))).isEmpty();
            assertThat(violatedFields(reg("darling", "a".repeat(65)))).contains("password");
            assertThat(violatedFields(reg("darling", null))).contains("password");
        }

        @Test
        @DisplayName("昵称最长 50 个字符")
        void nicknameMaxLength() {
            assertThat(violatedFields(new AuthDtos.RegisterRequest(
                    "darling", "study2026", "备".repeat(50), null))).isEmpty();
            assertThat(violatedFields(new AuthDtos.RegisterRequest(
                    "darling", "study2026", "备".repeat(51), null))).contains("nickname");
        }

        private AuthDtos.RegisterRequest reg(String username, String password) {
            return new AuthDtos.RegisterRequest(username, password, null, null);
        }
    }

    @Nested
    @DisplayName("新建任务请求")
    class CreateTask {

        private TaskDtos.CreateRequest req(Long subjectId, String title, LocalDate planDate,
                                           Integer planMinutes) {
            return new TaskDtos.CreateRequest(subjectId, title, planDate, planMinutes, null, null);
        }

        @Test
        @DisplayName("合法请求没有违规")
        void valid() {
            assertThat(violations(req(1L, "做一套真题", LocalDate.now(), 90))).isEmpty();
        }

        @Test
        @DisplayName("科目 / 标题 / 计划日期 / 计划时长 都是必填")
        void requiredFields() {
            assertThat(violatedFields(req(null, "做一套真题", LocalDate.now(), 90)))
                    .contains("subjectId");
            assertThat(violatedFields(req(1L, null, LocalDate.now(), 90))).contains("title");
            assertThat(violatedFields(req(1L, "   ", LocalDate.now(), 90))).contains("title");
            assertThat(violatedFields(req(1L, "做一套真题", null, 90))).contains("planDate");
            assertThat(violatedFields(req(1L, "做一套真题", LocalDate.now(), null)))
                    .contains("planMinutes");
        }

        @Test
        @DisplayName("★ 计划时长 1 ~ 1440 分钟（1440 = 一天 24 小时，是上限）")
        void planMinutesBoundaries() {
            assertThat(violatedFields(req(1L, "x", LocalDate.now(), 0))).contains("planMinutes");
            assertThat(violatedFields(req(1L, "x", LocalDate.now(), -1))).contains("planMinutes");
            assertThat(violatedFields(req(1L, "x", LocalDate.now(), 1))).isEmpty();
            assertThat(violatedFields(req(1L, "x", LocalDate.now(), 1440))).isEmpty();
            assertThat(violatedFields(req(1L, "x", LocalDate.now(), 1441))).contains("planMinutes");
        }

        @Test
        @DisplayName("标题最长 200、备注最长 500")
        void textLengths() {
            assertThat(violatedFields(new TaskDtos.CreateRequest(
                    1L, "题".repeat(200), LocalDate.now(), 90, null, null))).isEmpty();
            assertThat(violatedFields(new TaskDtos.CreateRequest(
                    1L, "题".repeat(201), LocalDate.now(), 90, null, null))).contains("title");
            assertThat(violatedFields(new TaskDtos.CreateRequest(
                    1L, "题", LocalDate.now(), 90, null, "记".repeat(501)))).contains("note");
        }

        @Test
        @DisplayName("状态变更请求的 status 必填")
        void statusRequired() {
            assertThat(violatedFields(new TaskDtos.StatusRequest(null))).contains("status");
            assertThat(violations(new TaskDtos.StatusRequest(
                    com.wpc725562.examtracker.domain.TaskStatus.DONE))).isEmpty();
        }
    }

    @Nested
    @DisplayName("新建打卡请求")
    class CreateCheckin {

        private CheckinDtos.CreateRequest req(Integer minutes) {
            return new CheckinDtos.CreateRequest(1L, null, LocalDate.now(), minutes, null);
        }

        @Test
        @DisplayName("★ 实际时长必填，且 1 ~ 1440 分钟")
        void actualMinutesBoundaries() {
            assertThat(violatedFields(req(null))).contains("actualMinutes");
            assertThat(violatedFields(req(0))).contains("actualMinutes");
            assertThat(violatedFields(req(1))).isEmpty();
            assertThat(violatedFields(req(1440))).isEmpty();
            assertThat(violatedFields(req(1441))).contains("actualMinutes");
        }

        @Test
        @DisplayName("★ subjectId / taskId 都可以为空 —— 二选一的规则在 Service 层判")
        void anchorsAreOptionalAtDtoLevel() {
            // 校验注解表达不了「二选一」，所以这条规则只能在 Service 里。
            // 这里显式记下「DTO 层不拦」，避免以后有人以为 DTO 已经保证了。
            assertThat(violations(new CheckinDtos.CreateRequest(
                    null, null, LocalDate.now(), 60, null))).isEmpty();
        }
    }

    @Nested
    @DisplayName("科目请求")
    class Subject {

        private SubjectDtos.CreateRequest req(String name, String color, Integer target) {
            return new SubjectDtos.CreateRequest(name, color, target, null);
        }

        @Test
        @DisplayName("颜色：只接受 #RGB 或 #RRGGBB，且可以不填")
        void colorPattern() {
            assertThat(violatedFields(req("数学", "#4F46E5", 420))).isEmpty();
            assertThat(violatedFields(req("数学", "#ABC", 420))).isEmpty();
            assertThat(violatedFields(req("数学", null, 420))).isEmpty();
            assertThat(violatedFields(req("数学", "#GGG", 420))).contains("color");
            assertThat(violatedFields(req("数学", "4F46E5", 420))).contains("color");
            assertThat(violatedFields(req("数学", "#12345", 420))).contains("color");
            assertThat(violatedFields(req("数学", "红色", 420))).contains("color");
        }

        @Test
        @DisplayName("★ 每周目标 0 ~ 10080 分钟（7×24×60）")
        void weeklyTargetBoundaries() {
            assertThat(violatedFields(req("数学", null, null))).contains("targetMinutesPerWeek");
            assertThat(violatedFields(req("数学", null, -1))).contains("targetMinutesPerWeek");
            assertThat(violatedFields(req("数学", null, 0))).isEmpty();
            assertThat(violatedFields(req("数学", null, 10080))).isEmpty();
            assertThat(violatedFields(req("数学", null, 10081))).contains("targetMinutesPerWeek");
        }

        @Test
        @DisplayName("科目名必填，最长 50")
        void nameRules() {
            assertThat(violatedFields(req("  ", null, 420))).contains("name");
            assertThat(violatedFields(req("数".repeat(50), null, 420))).isEmpty();
            assertThat(violatedFields(req("数".repeat(51), null, 420))).contains("name");
        }
    }
}
