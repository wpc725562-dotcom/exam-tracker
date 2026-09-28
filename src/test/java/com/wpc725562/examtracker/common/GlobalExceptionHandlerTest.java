package com.wpc725562.examtracker.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全局异常处理的映射规则。
 *
 * <p>直接调用处理器方法（不起 Spring 容器）：测的是「哪种异常 → 哪个状态码 / 什么响应体」
 * 这张映射表本身，跟 MVC 装配无关，跑得飞快。
 *
 * <p>最要紧的一条是兜底分支：**对外只能回一句固定文案**。
 * 一旦有人把 {@code ex.getMessage()} 拼进去，客户端就会看到表名、SQL 片段、
 * 文件路径这类内部信息 —— 这是很常见的安全事故，所以专门有一条断言守着。
 */
@DisplayName("GlobalExceptionHandler —— 异常到 HTTP 的映射")
class GlobalExceptionHandlerTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static HttpServletRequest request() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("GET");
        when(req.getRequestURI()).thenReturn("/api/tasks");
        return req;
    }

    /** 构造一个带字段错误的 MethodArgumentNotValidException。 */
    private static MethodArgumentNotValidException validationFailure() throws NoSuchMethodException {
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("sampleBody", String.class), 0);
        // 目标对象必须是一个**真的有这些属性**的 bean：用字符串之类的东西当 target，
        // rejectValue 会直接抛 NotReadableProperty。
        BindingResult binding = new BeanPropertyBindingResult(new SampleBody(), "sampleBody");
        binding.rejectValue("name", "NotBlank", "名字不能为空");
        binding.rejectValue("planMinutes", "Min", "计划时长至少 1 分钟");
        return new MethodArgumentNotValidException(parameter, binding);
    }

    @SuppressWarnings("unused")
    private void sampleBody(String body) {
    }

    /** 只为撑起 BindingResult 的属性名，字段本身不参与校验。 */
    public static class SampleBody {
        private String name;
        private Integer planMinutes;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getPlanMinutes() {
            return planMinutes;
        }

        public void setPlanMinutes(Integer planMinutes) {
            this.planMinutes = planMinutes;
        }
    }

    private record Sample(@NotBlank(message = "名字不能为空") String name) {
    }

    @Nested
    @DisplayName("业务异常")
    class Business {

        @Test
        @DisplayName("404 -> NOT_FOUND + 40400")
        void notFound() {
            ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(
                    BusinessException.notFound("任务"), request());

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(response.getBody().code()).isEqualTo(40400);
            assertThat(response.getBody().message()).isEqualTo("任务不存在");
            assertThat(response.getBody().data()).isNull();
        }

        @Test
        @DisplayName("409 -> CONFLICT + 40900")
        void conflict() {
            ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(
                    BusinessException.conflict("科目「语文」下还有 3 个任务"), request());

            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(response.getBody().code()).isEqualTo(40900);
            assertThat(response.getBody().message()).contains("语文");
        }

        @Test
        @DisplayName("400 -> INVALID_PARAM + 40000")
        void invalidParam() {
            ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(
                    BusinessException.invalidParam("不支持的排序字段：xxx"), request());

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().code()).isEqualTo(40000);
        }

        @Test
        @DisplayName("401 -> UNAUTHORIZED + 40100")
        void unauthorized() {
            ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(
                    BusinessException.unauthorized("用户名或密码错误"), request());

            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getBody().code()).isEqualTo(40100);
            assertThat(response.getBody().message()).isEqualTo("用户名或密码错误");
        }
    }

    @Nested
    @DisplayName("参数类异常 -> 一律 400")
    class BadRequest {

        @Test
        @DisplayName("@Valid 失败 -> 400，且把所有字段的错误都返回（不是只报第一条）")
        void bodyValidation() throws Exception {
            ResponseEntity<ApiResponse<Map<String, String>>> response =
                    handler.handleValidation(validationFailure());

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().code()).isEqualTo(40000);
            assertThat(response.getBody().data())
                    .containsEntry("name", "名字不能为空")
                    .containsEntry("planMinutes", "计划时长至少 1 分钟");
        }

        @Test
        @DisplayName("@Validated 失败 -> 400，参数名会去掉方法名前缀")
        void methodParamValidation() {
            Set<ConstraintViolation<Sample>> violations = VALIDATOR.validate(new Sample(null));
            assertThat(violations).hasSize(1);

            ResponseEntity<ApiResponse<Map<String, String>>> response =
                    handler.handleConstraintViolation(
                            new ConstraintViolationException(new HashSet<>(violations)));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().data()).containsEntry("name", "名字不能为空");
        }

        @Test
        @DisplayName("请求体不是合法 JSON -> 400")
        void unreadableBody() {
            ResponseEntity<ApiResponse<Void>> response = handler.handleUnreadable(
                    new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0])));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().code()).isEqualTo(40000);
        }

        @Test
        @DisplayName("缺必填查询参数 -> 400，并指出是哪个参数")
        void missingParam() {
            ResponseEntity<ApiResponse<Void>> response = handler.handleMissingParam(
                    new MissingServletRequestParameterException("keyword", "String"));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().message()).contains("keyword");
        }

        @Test
        @DisplayName("参数类型不对（?page=abc）-> 400")
        void typeMismatch() throws Exception {
            MethodParameter parameter = new MethodParameter(
                    GlobalExceptionHandlerTest.class.getDeclaredMethod("sampleBody", String.class), 0);

            ResponseEntity<ApiResponse<Void>> response = handler.handleTypeMismatch(
                    new MethodArgumentTypeMismatchException(
                            "abc", Integer.class, "page", parameter, new NumberFormatException()));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().message()).contains("page");
        }
    }

    @Nested
    @DisplayName("认证 / 权限")
    class Auth {

        @Test
        @DisplayName("认证失败 -> 401，只回通用文案（不透露是密码错还是 token 过期）")
        void authentication() {
            ResponseEntity<ApiResponse<Void>> response = handler.handleAuthentication(
                    new BadCredentialsException("Bad credentials"));

            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getBody().code()).isEqualTo(40100);
            assertThat(response.getBody().message())
                    .isEqualTo(ErrorCode.UNAUTHORIZED.defaultMessage());
        }

        @Test
        @DisplayName("权限不足 -> 403")
        void accessDenied() {
            ResponseEntity<ApiResponse<Void>> response =
                    handler.handleAccessDenied(new AccessDeniedException("nope"));

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            assertThat(response.getBody().code()).isEqualTo(40300);
        }
    }

    @Nested
    @DisplayName("路径 / 方法")
    class Routing {

        @Test
        @DisplayName("静态资源未命中 -> 404")
        void noResource() {
            ResponseEntity<ApiResponse<Void>> response = handler.handleNoResource(
                    new NoResourceFoundException(HttpMethod.GET, "/api/nope"));

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(response.getBody().code()).isEqualTo(40400);
        }

        @Test
        @DisplayName("★ 完全没有匹配的 handler -> 404（不是 500）")
        void noHandler() {
            ResponseEntity<ApiResponse<Void>> response = handler.handleNoHandler(
                    new NoHandlerFoundException("GET", "/api/nope", new HttpHeaders()));

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(response.getBody().code()).isEqualTo(40400);
        }

        @Test
        @DisplayName("HTTP 方法不支持 -> 405")
        void methodNotSupported() {
            ResponseEntity<ApiResponse<Void>> response = handler.handleMethodNotSupported(
                    new HttpRequestMethodNotSupportedException("PUT"));

            assertThat(response.getStatusCode().value()).isEqualTo(405);
            assertThat(response.getBody().message()).contains("PUT");
        }
    }

    @Nested
    @DisplayName("兜底")
    class Fallback {

        @Test
        @DisplayName("★ 未预期异常 -> 500，且响应体里**不能**出现异常原文（防泄漏表名 / SQL / 路径）")
        void unexpectedDoesNotLeakInternals() {
            String secret = "表 app_user 的 password_hash 列不存在，SQL: select * from app_user";

            ResponseEntity<ApiResponse<Void>> response =
                    handler.handleUnexpected(new IllegalStateException(secret), request());

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody().code()).isEqualTo(50000);
            assertThat(response.getBody().message()).isEqualTo("服务器内部错误");
            assertThat(response.getBody().data()).isNull();
            assertThat(response.getBody().toString())
                    .as("对外响应绝不能带上异常原文")
                    .doesNotContain("app_user")
                    .doesNotContain("password_hash")
                    .doesNotContain("select");
        }

        @Test
        @DisplayName("NullPointerException 之类的 RuntimeException 也走同一条兜底")
        void runtimeExceptionAlsoFallsBack() {
            ResponseEntity<ApiResponse<Void>> response =
                    handler.handleUnexpected(new NullPointerException("payload 里的 userId 是 null"), request());

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody().toString()).doesNotContain("payload");
        }
    }
}
