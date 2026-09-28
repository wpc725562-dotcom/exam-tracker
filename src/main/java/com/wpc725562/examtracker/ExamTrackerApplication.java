package com.wpc725562.examtracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * 备考任务追踪 API 的入口。
 *
 * <p>这个服务把「每天要做什么、做了多少、四科进度如何」这三件事做成一套 REST 接口。
 * 它替代的是原先散落在前端页面里的计算逻辑 —— 打卡、连续天数、完成率这些
 * 都要有唯一的事实来源，放在前端算迟早会出现两个页面数字对不上。
 *
 * <p>{@code @EnableJpaAuditing} 用来支持实体上的 {@code @CreatedDate} / {@code @LastModifiedDate}，
 * 这样「谁在什么时候改的」由框架统一填，不靠每个 Service 手写。
 */
@SpringBootApplication
@EnableJpaAuditing
@ConfigurationPropertiesScan
public class ExamTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExamTrackerApplication.class, args);
    }
}
