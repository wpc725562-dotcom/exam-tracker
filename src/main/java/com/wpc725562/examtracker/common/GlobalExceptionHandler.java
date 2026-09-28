package com.wpc725562.examtracker.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理。
 *
 * <p>存在的意义是让「错误长什么样」只有一处定义。没有它的话，每个 Controller
 * 都要写 try/catch，而且很容易漏 —— 漏掉的那个会直接把 Java 堆栈吐给客户端，
 * 既难看又泄漏内部结构（包名、类名、SQL 片段）。
 *
 * <p><b>两条原则：</b>
 * <ol>
 *   <li><b>HTTP 状态码要正确。</b>业务失败也返回 200 会让监控、网关重试、
 *       浏览器缓存全部失准。</li>
 *   <li><b>对外只说人话，细节只进日志。</b>{@code Exception} 兜底时只回
 *       「服务器内部错误」，真正的堆栈用 {@code log.error} 记在服务端。</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常 —— 唯一「预期内」的失败路径。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex, HttpServletRequest req) {
        // 业务异常是正常流程的一部分，用 warn 而不是 error：
        // 用 error 会让真正的故障淹没在「用户输错密码」这类噪声里。
        log.warn("业务异常 {} {} -> {}: {}", req.getMethod(), req.getRequestURI(),
                ex.getErrorCode().name(), ex.getMessage());
        return ResponseEntity.status(ex.getErrorCode().httpStatus())
                .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
    }

    /**
     * {@code @Valid} 校验失败（请求体）。
     *
     * <p>把每个字段的错误都收进 data 里，而不是只报第一条 ——
     * 表单场景下一次性告诉用户所有问题，比让他改一个提交一次体验好得多。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(
            MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() == null ? "不合法" : fe.getDefaultMessage(),
                        // 同一字段多条规则同时失败时保留第一条，不要抛 IllegalStateException
                        (first, second) -> first,
                        LinkedHashMap::new));
        log.warn("参数校验失败: {}", fieldErrors);
        return ResponseEntity.badRequest()
                .body(new ApiResponse<>(ErrorCode.INVALID_PARAM.code(),
                        ErrorCode.INVALID_PARAM.defaultMessage(), fieldErrors));
    }

    /** {@code @Validated} 校验失败（方法参数，比如 {@code @RequestParam @Min(1)}）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleConstraintViolation(
            ConstraintViolationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            // propertyPath 形如 "listTasks.page" —— 去掉方法名前缀，只留参数名
            String path = v.getPropertyPath().toString();
            int dot = path.lastIndexOf('.');
            errors.put(dot >= 0 ? path.substring(dot + 1) : path, v.getMessage());
        }
        log.warn("参数约束失败: {}", errors);
        return ResponseEntity.badRequest()
                .body(new ApiResponse<>(ErrorCode.INVALID_PARAM.code(),
                        ErrorCode.INVALID_PARAM.defaultMessage(), errors));
    }

    /** 请求体不是合法 JSON，或缺必填字段。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("请求体无法解析: {}", ex.getMostSpecificCause().getMessage());
        return badRequest("请求体格式错误，请检查 JSON 是否合法、字段类型是否匹配");
    }

    /** 必填的查询参数没传。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return badRequest("缺少必填参数：" + ex.getParameterName());
    }

    /** 路径变量 / 查询参数类型不对，比如 {@code ?page=abc}。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("参数 " + ex.getName() + " 的取值不合法：" + ex.getValue());
    }

    /** 请求方法不支持，比如用 GET 调了只接受 POST 的接口。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(405)
                .body(ApiResponse.error(ErrorCode.INVALID_PARAM,
                        "该接口不支持 " + ex.getMethod() + " 方法"));
    }

    /** 认证失败（token 无效、过期、缺失）。 */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        log.warn("认证失败: {}", ex.getMessage());
        return ResponseEntity.status(401)
                .body(ApiResponse.error(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage()));
    }

    /** 已认证但权限不够（{@code @PreAuthorize} 拦截）。 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("权限不足: {}", ex.getMessage());
        return ResponseEntity.status(403)
                .body(ApiResponse.error(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage()));
    }

    /**
     * 路径不存在。
     *
     * <p>Spring Boot 3.2 起静态资源未命中抛的是 {@code NoResourceFoundException}，
     * 不处理的话会被下面的兜底分支当成 500，把「404」误报成「服务器错误」。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(404)
                .body(ApiResponse.error(ErrorCode.NOT_FOUND, "接口不存在：" + ex.getResourcePath()));
    }

    /**
     * 没有任何 handler 匹配这个路径。
     *
     * <p>和上面的 {@code NoResourceFoundException} 是一对：默认情况下静态资源处理器
     * 会先接住未命中的路径并抛前者；但如果把静态资源映射关掉
     * （{@code spring.web.resources.add-mappings=false}，纯 API 服务常见做法），
     * 请求就会走到这里。**不处理的话同样会被兜底成 500** ——
     * 把「用户把路径拼错了」报成「服务器内部错误」，
     * 既误导前端也污染告警。
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandler(NoHandlerFoundException ex) {
        return ResponseEntity.status(404)
                .body(ApiResponse.error(ErrorCode.NOT_FOUND, "接口不存在：" + ex.getRequestURL()));
    }

    /**
     * 兜底。
     *
     * <p>只有走到这里才是真正的「服务器内部错误」，所以这里用 {@code log.error}
     * 并把完整堆栈打出来 —— 而**对外只回一句固定文案**，绝不把
     * {@code ex.getMessage()} 返回给客户端：那里面常常带着表名、SQL、文件路径。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("未预期异常 {} {}", req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.status(500)
                .body(ApiResponse.error(ErrorCode.INTERNAL, ErrorCode.INTERNAL.defaultMessage()));
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_PARAM, message));
    }
}
