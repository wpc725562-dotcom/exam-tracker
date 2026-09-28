package com.wpc725562.examtracker.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * AI 层的 DTO。
 *
 * <p>工具方法的返回类型也定义在这里 —— 它们同样是对外的「接口」
 * （模型看到的就是这些字段的 JSON），放在一起才看得出「模型到底能看到什么」。
 */
public final class AiDtos {

    private AiDtos() {
    }

    @Schema(description = "自然语言提问")
    public record AskRequest(

            @Schema(description = "用自然语言问的问题", example = "我这周数学做了多久？")
            @NotBlank(message = "问题不能为空")
            @Size(max = 500, message = "问题最长 500 个字符")
            String question,

            /**
             * 会话 id。不传就开一段新对话。
             *
             * <p>由<b>服务端</b>生成并返回，前端只需把它原样带回来。
             * 不让前端自己造 id：那样「会话 id 撞车」就变成了两个用户共用一段上下文，
             * 而这是最难查的一类问题。
             */
            @Schema(description = "会话 id，不传则开启新对话；续聊时原样回传上一轮返回的值",
                    example = "b1f3c2a4-…")
            @Size(max = 64, message = "会话 id 最长 64 个字符")
            String conversationId
    ) {
    }

    @Schema(description = "AI 回答")
    public record AnswerResponse(

            @Schema(description = "用自然语言组织的回答")
            String answer,

            @Schema(description = "本次回答调用了哪些工具（按调用顺序），前端可折叠展示")
            List<String> toolsUsed,

            @Schema(description = "会话 id。续聊时把它带回来即可")
            String conversationId,

            @Schema(description = "是否降级回答：模型不可用、超时或工具执行出错时为 true，"
                    + "此时 answer 里是明确的失败说明而**不是**空字符串")
            boolean degraded,

            @Schema(description = "服务端处理耗时（毫秒）")
            long elapsedMs
    ) {
    }

    // ---------------------------------------------------------------------
    //  下面是工具方法的返回类型 —— 也就是「模型能看到的数据形状」。
    //  刻意比 REST 的 DTO 更窄：模型不需要 id、创建时间这些字段，
    //  给了只会占 token，并且增加它拿这些数字去编参数的机会。
    // ---------------------------------------------------------------------

    @Schema(description = "工具返回：精简后的任务")
    public record TaskBrief(
            Long id,
            String subjectName,
            String title,
            LocalDate planDate,
            Integer planMinutes,
            String priority,
            String status
    ) {
    }

    @Schema(description = "工具返回：任务检索结果")
    public record TaskSearchResult(
            @Schema(description = "符合条件的总条数（不受本页条数限制）") long matched,
            @Schema(description = "本次返回的任务，最多若干条") List<TaskBrief> tasks,
            @Schema(description = "需要提醒模型注意的说明，没有则为 null") String note
    ) {
    }

    @Schema(description = "工具返回：某科目的每日投入")
    public record DailyMinutesResult(
            @Schema(description = "科目名，未指定科目时为「全部科目」") String subject,
            LocalDate from,
            LocalDate to,
            @Schema(description = "区间内总投入分钟数") long totalMinutes,
            @Schema(description = "逐日明细") List<DayMinutes> days,
            @Schema(description = "需要提醒模型注意的说明，没有则为 null") String note
    ) {
    }

    @Schema(description = "工具返回：某一天的投入")
    public record DayMinutes(LocalDate date, long minutes) {
    }
}
