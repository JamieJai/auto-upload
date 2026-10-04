package com.autoreg.llm;

import tools.jackson.databind.JsonNode;

/**
 * 구조화된 JSON 을 돌려주는 LLM 호출. 구현을 바꿔도(구독 CLI → API 키) 호출부는 그대로다.
 * 실패 시 LlmException (retryable 여부 포함).
 */
public interface LlmClient {

    JsonNode generate(String systemPrompt, String userPrompt, String jsonSchema);
}
