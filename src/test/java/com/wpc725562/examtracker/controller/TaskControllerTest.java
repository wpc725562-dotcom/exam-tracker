package com.wpc725562.examtracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wpc725562.examtracker.common.BusinessException;
import com.wpc725562.examtracker.common.GlobalExceptionHandler;
import com.wpc725562.examtracker.common.PageResult;
import com.wpc725562.examtracker.domain.Priority;
import com.wpc725562.examtracker.domain.TaskStatus;
import com.wpc725562.examtracker.dto.TaskDtos;
import com.wpc725562.examtracker.dto.TaskFilter;
import com.wpc725562.examtracker.security.UserPrincipal;
import com.wpc725562.examtracker.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务接口的 Web 层。
 *
 * <p>这里测的是**控制器与 Service 之间的契约**，尤其是：
 * <ul>
 *   <li>★ 当前登录用户的 id 必须从 token（{@code @AuthenticationPrincipal}）来，
 *       而不是从请求参数来 —— 否则谁都能传别人的 userId；</li>
 *   <li>Service 抛出的业务异常要映射成正确的 HTTP 状态码，而不是统一 500。</li>
 * </ul>
 *
 * <p>用 standalone 装配（不启动 Spring 容器）：只装这一个控制器，
 * 不碰数据库、不碰安全过滤链，跑得快而且不会因为环境问题假红。
 * 参数校验的行为由 {@code ValidationTest}（注解本身）和端到端脚本（真实 HTTP）覆盖。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskController —— Web 层契约")
class TaskControllerTest {

    private static final Long USER_ID = 42L;

    @Mock
    private TaskService taskService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new TaskController(taskService))
                .setControllerAdvice(new GlobalExceptionHandler())
                // standalone 装配不会自动带上 Spring Security 的参数解析器，
                // 而 @AuthenticationPrincipal 正是靠它从 SecurityContext 取值的。
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        // 模拟「JWT 过滤器已经把当前用户放进 SecurityContext」
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new UserPrincipal(USER_ID, "darling"), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static TaskDtos.TaskResponse sampleTask() {
        return new TaskDtos.TaskResponse(7L, 10L, "数学", "做一套真题",
                LocalDate.of(2026, 9, 28), 90, Priority.HIGH, TaskStatus.TODO,
                "错题整理", null, null, null);
    }

    @Nested
    @DisplayName("列表")
    class ListEndpoint {

        @Test
        @DisplayName("★ 查询用的是 token 里的 userId，不是请求参数里的")
        void usesPrincipalUserId() throws Exception {
            when(taskService.list(eq(USER_ID), any(TaskFilter.class), anyInt(), anyInt(), any(), any()))
                    .thenReturn(new PageResult<>(List.of(sampleTask()), 1, 20, 1, 1));

            mockMvc.perform(get("/tasks"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.page").value(1))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.items[0].title").value("做一套真题"))
                    .andExpect(jsonPath("$.data.items[0].subjectName").value("数学"));

            verify(taskService).list(eq(USER_ID), any(TaskFilter.class), eq(1), eq(20), isNull(), isNull());
        }

        @Test
        @DisplayName("筛选参数会被原样组装进 TaskFilter")
        void filterIsParsed() throws Exception {
            when(taskService.list(eq(USER_ID), any(TaskFilter.class), anyInt(), anyInt(), any(), any()))
                    .thenReturn(new PageResult<>(List.of(), 1, 20, 0, 0));

            mockMvc.perform(get("/tasks")
                            .param("from", "2026-09-01")
                            .param("to", "2026-09-30")
                            .param("subjectId", "10")
                            .param("status", "DONE")
                            .param("priority", "HIGH")
                            .param("keyword", "真题"))
                    .andExpect(status().isOk());

            ArgumentCaptor<TaskFilter> captor = ArgumentCaptor.forClass(TaskFilter.class);
            verify(taskService).list(eq(USER_ID), captor.capture(), anyInt(), anyInt(), any(), any());

            TaskFilter filter = captor.getValue();
            assertThat(filter.from()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(filter.to()).isEqualTo(LocalDate.of(2026, 9, 30));
            assertThat(filter.subjectId()).isEqualTo(10L);
            assertThat(filter.status()).isEqualTo(TaskStatus.DONE);
            assertThat(filter.priority()).isEqualTo(Priority.HIGH);
            assertThat(filter.keyword()).isEqualTo("真题");
        }

        @Test
        @DisplayName("分页与排序参数会透传")
        void pagingAndSortArePassedThrough() throws Exception {
            when(taskService.list(eq(USER_ID), any(TaskFilter.class), anyInt(), anyInt(), any(), any()))
                    .thenReturn(new PageResult<>(List.of(), 1, 20, 0, 0));

            mockMvc.perform(get("/tasks")
                            .param("page", "3").param("size", "50")
                            .param("sortBy", "planMinutes").param("direction", "desc"))
                    .andExpect(status().isOk());

            verify(taskService).list(eq(USER_ID), any(TaskFilter.class), eq(3), eq(50),
                    eq("planMinutes"), eq("desc"));
        }

        @Test
        @DisplayName("不传 page/size 时用默认值 1 / 20")
        void defaultsAreApplied() throws Exception {
            when(taskService.list(eq(USER_ID), any(TaskFilter.class), anyInt(), anyInt(), any(), any()))
                    .thenReturn(new PageResult<>(List.of(), 1, 20, 0, 0));

            mockMvc.perform(get("/tasks")).andExpect(status().isOk());

            verify(taskService).list(eq(USER_ID), any(TaskFilter.class), eq(1), eq(20), isNull(), isNull());
        }

        @Test
        @DisplayName("状态枚举传了非法值 -> 400（而不是 500）")
        void invalidEnumIsBadRequest() throws Exception {
            mockMvc.perform(get("/tasks").param("status", "NOT_A_STATUS"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }

        @Test
        @DisplayName("日期格式不对 -> 400")
        void invalidDateIsBadRequest() throws Exception {
            mockMvc.perform(get("/tasks").param("from", "2026/09/01"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
    }

    @Nested
    @DisplayName("详情")
    class GetEndpoint {

        @Test
        @DisplayName("★ 用 token 的 userId + 路径 id 去查（越权由查询条件挡住）")
        void usesPrincipalUserId() throws Exception {
            when(taskService.get(USER_ID, 7L)).thenReturn(sampleTask());

            mockMvc.perform(get("/tasks/7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(7))
                    .andExpect(jsonPath("$.data.status").value("TODO"));

            verify(taskService).get(USER_ID, 7L);
        }

        @Test
        @DisplayName("★ 查不到（含「是别人的」）-> 404 + 业务码 40400")
        void notFound() throws Exception {
            when(taskService.get(USER_ID, 99L)).thenThrow(BusinessException.notFound("任务"));

            mockMvc.perform(get("/tasks/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(40400))
                    .andExpect(jsonPath("$.message").value("任务不存在"));
        }
    }

    @Nested
    @DisplayName("新建")
    class CreateEndpoint {

        private static final String BODY = """
                {"subjectId":10,"title":"做一套真题","planDate":"2026-09-28",
                 "planMinutes":90,"priority":"HIGH","note":"错题整理"}
                """;

        @Test
        @DisplayName("★ 201 Created，且 Service 收到的是 token 里的 userId")
        void created() throws Exception {
            when(taskService.create(eq(USER_ID), any(TaskDtos.CreateRequest.class)))
                    .thenReturn(sampleTask());

            mockMvc.perform(post("/tasks")
                            .contentType("application/json").content(BODY))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.title").value("做一套真题"));

            ArgumentCaptor<TaskDtos.CreateRequest> captor =
                    ArgumentCaptor.forClass(TaskDtos.CreateRequest.class);
            verify(taskService).create(eq(USER_ID), captor.capture());
            assertThat(captor.getValue().subjectId()).isEqualTo(10L);
            assertThat(captor.getValue().planMinutes()).isEqualTo(90);
        }

        @Test
        @DisplayName("科目不属于当前用户 -> 404")
        void subjectNotOwned() throws Exception {
            when(taskService.create(eq(USER_ID), any(TaskDtos.CreateRequest.class)))
                    .thenThrow(BusinessException.notFound("科目"));

            mockMvc.perform(post("/tasks")
                            .contentType("application/json").content(BODY))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("科目不存在"));
        }

        @Test
        @DisplayName("请求体不是合法 JSON -> 400")
        void malformedJson() throws Exception {
            mockMvc.perform(post("/tasks")
                            .contentType("application/json").content("{not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
    }

    @Nested
    @DisplayName("状态流转")
    class ChangeStatusEndpoint {

        @Test
        @DisplayName("PATCH /tasks/{id}/status -> 把枚举交给 Service")
        void changesStatus() throws Exception {
            when(taskService.changeStatus(eq(USER_ID), eq(7L), any(TaskDtos.StatusRequest.class)))
                    .thenReturn(sampleTask());

            mockMvc.perform(patch("/tasks/7/status")
                            .contentType("application/json").content("{\"status\":\"DONE\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));

            ArgumentCaptor<TaskDtos.StatusRequest> captor =
                    ArgumentCaptor.forClass(TaskDtos.StatusRequest.class);
            verify(taskService).changeStatus(eq(USER_ID), eq(7L), captor.capture());
            assertThat(captor.getValue().status()).isEqualTo(TaskStatus.DONE);
        }

        @Test
        @DisplayName("状态枚举非法 -> 400")
        void invalidEnum() throws Exception {
            mockMvc.perform(patch("/tasks/7/status")
                            .contentType("application/json").content("{\"status\":\"DOING\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
    }

    @Nested
    @DisplayName("删除")
    class DeleteEndpoint {

        @Test
        @DisplayName("用 token 的 userId 删除，返回统一成功外壳")
        void deletes() throws Exception {
            mockMvc.perform(delete("/tasks/7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.message").value("成功"));

            verify(taskService).delete(USER_ID, 7L);
        }
    }

    @Nested
    @DisplayName("异常映射")
    class ExceptionMapping {

        @Test
        @DisplayName("★ 业务冲突 -> 409（不是 500）")
        void conflict() throws Exception {
            when(taskService.get(USER_ID, 1L)).thenThrow(BusinessException.conflict("数据冲突"));

            mockMvc.perform(get("/tasks/1"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(40900));
        }

        @Test
        @DisplayName("★ 参数不合法 -> 400（不是 500）")
        void invalidParam() throws Exception {
            when(taskService.get(USER_ID, 1L))
                    .thenThrow(BusinessException.invalidParam("不支持的排序字段"));

            mockMvc.perform(get("/tasks/1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }

        @Test
        @DisplayName("★ 未预期异常 -> 500，且对外只回固定文案（不泄漏内部信息）")
        void unexpectedExceptionDoesNotLeak() throws Exception {
            when(taskService.get(USER_ID, 1L))
                    .thenThrow(new IllegalStateException("表 app_user 的 password_hash 列不存在"));

            mockMvc.perform(get("/tasks/1"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(50000))
                    .andExpect(jsonPath("$.message").value("服务器内部错误"));
        }
    }

    @Test
    @DisplayName("不存在的路径 -> 404（不会因为没写 handler 而变成 500）")
    void unknownPathIsNotFound() throws Exception {
        mockMvc.perform(get("/tasks/1/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("错误的 HTTP 方法 -> 405")
    void wrongMethod() throws Exception {
        mockMvc.perform(delete("/tasks"))
                .andExpect(status().isMethodNotAllowed());
    }
}
