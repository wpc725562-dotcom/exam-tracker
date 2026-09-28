package com.wpc725562.examtracker.ai;

/**
 * 自然语言问答的入口。
 *
 * <p><b>为什么要有这个接口：</b>它把「和 LLM 通信」这件事和「怎么组织这次问答」
 * 隔开。测试里可以换成一个返回固定答案的替身，于是
 * <b>整条链路（控制器 → 服务 → 返回体结构）都能在 CI 里跑</b>，
 * 不需要 API key、不会因为模型输出不同而时红时绿。
 *
 * <p>这正是「LLM 调用本身不进 CI」这条规则的具体落地方式：
 * 不是「不测」，而是把不可确定的那一小块隔离掉，剩下的照测。
 */
public interface AiAssistant {

    /**
     * 回答一个问题。
     *
     * @param userId         当前登录用户 id。由控制器从 {@code SecurityContext} 取，
     *                       <b>永远不来自请求体</b> —— 否则用户就能查别人的数据
     * @param question       用户的问题原文
     * @param conversationId 会话 id；为 null 或空表示开一段新对话
     * @return 回答与元信息
     * @throws com.wpc725562.examtracker.common.BusinessException 模型不可用（超时/配额/网络）时，
     *         错误码为 {@code UNAVAILABLE}（HTTP 503）——
     *         <b>绝不静默返回空答案</b>：那会被用户误读成「我没有数据」
     */
    AiDtos.AnswerResponse ask(Long userId, String question, String conversationId);
}
