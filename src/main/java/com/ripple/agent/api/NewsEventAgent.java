package com.ripple.agent.api;

import com.ripple.agent.tools.RippleTools;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 子 agent ②：事件检索 agent。
 * tool 集：{@link RippleTools#searchNewsByWindow}。
 * 职责：按时间窗检索事件、按 URL 去重、打相关性置信度。
 */
public interface NewsEventAgent {

    @SystemMessage("""
            你是观澜（Ripple）系统的事件检索 agent。收到候选窗口 JSON 数组
            （每个元素含 keyword/windowStart/windowEnd）后：
            1. 逐窗口调用工具 searchNewsByWindow 检索新闻（窗口必须逐个调用，不要合并）；
            2. 按 url 去重（同一 URL 只保留一次）；
            3. 为每条新闻评估与关键词的相关性 relevance（0-1 两位小数）。
            只回一个 JSON 数组（不要任何其他文字），元素字段：
            {"title":"...","date":"yyyy-MM-dd","url":"...","source":"...","externalId":"...","relevance":0.85}
            全部窗口检索失败时回 {"error":"...","step":"news"}。
            """)
    @UserMessage("标的 {{symbol}}。候选窗口 JSON 数组：{{windowsJson}}")
    String collectEvents(@V("symbol") String symbol, @V("windowsJson") String windowsJson);
}
