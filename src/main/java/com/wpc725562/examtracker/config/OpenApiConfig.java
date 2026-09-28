package com.wpc725562.examtracker.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 接口文档配置。
 *
 * <p>注册一个 {@code bearerAuth} 安全方案，这样 Swagger UI 右上角会出现
 * 「Authorize」按钮 —— 登录拿到 token 后粘进去，后面所有接口都会自动带上
 * {@code Authorization: Bearer ...}，不用每个接口手工加 header。
 *
 * <p>不做这一步的话，文档页里除了登录接口以外的所有接口点「Try it out」都会返回 401，
 * 会让人误以为接口坏了。
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI examTrackerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("备考任务追踪 API")
                        .version("1.0.0")
                        .description("""
                                把「每天要做什么、实际做了多少、四科进度如何」做成一套 REST 接口。

                                **使用方式**：先调 `POST /auth/register` 注册，再调 `POST /auth/login` 拿 token，
                                然后点右上角 **Authorize** 把 token 填进去（只填 token 本身，不用加 `Bearer ` 前缀）。

                                **数据隔离**：所有业务接口都按当前登录用户过滤，不同账号之间的数据互不可见。
                                """)
                        .contact(new Contact().name("wpc725562-dotcom")
                                .url("https://github.com/wpc725562-dotcom"))
                        .license(new License().name("MIT")))
                .servers(List.of(
                        new Server().url("/api").description("当前服务")))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME,
                        new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 token")))
                // 全局默认要求认证；公开接口在 Controller 上用 @SecurityRequirements 单独标注
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }
}
