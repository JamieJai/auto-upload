package com.autoreg.llm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Claude Max 구독으로 로그인된 Claude Code CLI 를 비대화형으로 한 번씩 실행한다.
 * <pre>claude -p &lt;prompt&gt; --system-prompt ... --json-schema ... --output-format json --tools ""</pre>
 * Claude Code 기본 시스템 프롬프트와 도구를 빼서 호출을 가볍게 한다. 동시 실행 수는 세마포어로 제한한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "autoreg.llm.provider", havingValue = "claude-cli", matchIfMissing = true)
public class ClaudeCliLlmClient implements LlmClient {

    private final String command;
    private final String model;
    private final Duration timeout;
    private final Semaphore permits;
    private final JsonMapper json;

    public ClaudeCliLlmClient(
            @Value("${autoreg.llm.command:claude}") String command,
            @Value("${autoreg.llm.model:sonnet}") String model,
            @Value("${autoreg.llm.timeout-seconds:180}") long timeoutSeconds,
            @Value("${autoreg.llm.max-concurrency:2}") int maxConcurrency,
            JsonMapper json) {
        this.command = command;
        this.model = model;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.permits = new Semaphore(maxConcurrency, true);
        this.json = json;
    }

    @Override
    public JsonNode generate(String systemPrompt, String userPrompt, String jsonSchema) {
        try {
            if (!permits.tryAcquire(timeout.toSeconds(), TimeUnit.SECONDS)) {
                throw new LlmException("LLM 동시 실행 대기 시간 초과", true, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("중단됨", true, null);
        }
        try {
            return run(systemPrompt, userPrompt, jsonSchema);
        } finally {
            permits.release();
        }
    }

    private JsonNode run(String systemPrompt, String userPrompt, String jsonSchema) {
        List<String> cmd = List.of(command, "-p", userPrompt,
                "--model", model,
                "--system-prompt", systemPrompt,
                "--json-schema", jsonSchema,
                "--output-format", "json",
                "--tools", "",
                "--setting-sources", "",
                "--strict-mcp-config",
                "--no-session-persistence");
        Process p;
        try {
            p = new ProcessBuilder(cmd)
                    .directory(Path.of(System.getProperty("java.io.tmpdir")).toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                    .start();
        } catch (IOException e) {
            throw new LlmException("Claude CLI 를 실행할 수 없습니다 (" + command + "): " + e.getMessage(), false, null);
        }
        CompletableFuture<String> out = drain(p.getInputStream());
        CompletableFuture<String> err = drain(p.getErrorStream());
        try {
            if (!p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new LlmException("LLM 응답 시간 초과 (" + timeout.toSeconds() + "초)", true, null);
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new LlmException("중단됨", true, null);
        }
        String stdout = out.join();
        String stderr = err.join();
        JsonNode root;
        try {
            root = json.readTree(stdout);
        } catch (RuntimeException e) {
            throw classify("exit=" + p.exitValue() + " " + tail(stderr.isBlank() ? stdout : stderr));
        }
        if (root.path("is_error").asBoolean(false) || !"success".equals(root.path("subtype").asString(""))) {
            throw classify(root.path("subtype").asString("") + " " + root.path("api_error_status").asString("") + " "
                    + tail(root.path("result").asString("")));
        }
        JsonNode structured = root.get("structured_output");
        if (structured == null || !structured.isObject()) {
            throw new LlmException("LLM 이 구조화된 응답을 주지 않았습니다", true, null);
        }
        log.info("llm ok model={} turns={} ms={}", model, root.path("num_turns").asInt(), root.path("duration_ms").asLong());
        return structured;
    }

    /** 한도·과부하·인증 만료를 구분한다. 인증 만료는 사람이 다시 로그인해야 하므로 재시도해도 소용없다 */
    static LlmException classify(String detail) {
        String d = detail.toLowerCase(Locale.ROOT);
        if (d.contains("login") || d.contains("oauth") || d.contains("401") || d.contains("authentication")) {
            return new LlmException("Claude 로그인이 만료되었습니다. auto-app 에서 claude 로 다시 로그인하세요: " + detail.trim(), false, null);
        }
        if (d.contains("limit") || d.contains("429") || d.contains("quota")) {
            return new LlmException("Claude 사용량 한도: " + detail.trim(), true, Duration.ofMinutes(30));
        }
        return new LlmException("LLM 호출 실패: " + detail.trim(), true, null);
    }

    private static CompletableFuture<String> drain(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in; ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
                in.transferTo(buf);
                return buf.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    private static String tail(String s) {
        String t = s == null ? "" : s.strip();
        return t.length() > 300 ? t.substring(t.length() - 300) : t;
    }
}
