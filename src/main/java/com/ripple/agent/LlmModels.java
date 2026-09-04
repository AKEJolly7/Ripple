package com.ripple.agent;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.apache.logging.log4j.util.Strings;

import java.time.Duration;

/**
 * LLM 工厂：OpenAI 兼容协议直连 DeepSeek。
 * 密钥只从环境变量 DEEPSEEK_API_KEY 读取（System.getenv），严禁落盘/入日志/入异常消息。
 * available() 为 false 时上层自动走 --no-llm 规则对齐，保证无 key 也能跑通全流程。
 */
public final class LlmModels {

    public static final String BASE_URL = "https://api.deepseek.com";
    public static final String MODEL_NAME = "deepseek-chat";

    /**
     * 环境变量中是否存在可用 key（不读取值，只判存在）。
     */
    public static boolean available() {
        return !Strings.isBlank(System.getenv("DEEPSEEK_API_KEY"));
    }

    public static OpenAiChatModel deepseek() {
        return OpenAiChatModel.builder()
                .baseUrl(BASE_URL)
                .apiKey(System.getenv("DEEPSEEK_API_KEY"))
                .modelName(MODEL_NAME)
                .temperature(0.2)                    // 对齐任务要稳定，低温
                .timeout(Duration.ofSeconds(90))
                .build();
    }

    private LlmModels() {
    }
}
