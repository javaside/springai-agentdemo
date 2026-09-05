package io.github.javaside.springai.codetui.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/** Controls whether main-agent streaming retries are enabled at the L1 and L2 layers. */
public record StreamRetryConfig(StreamRetryMode mode) {

    public static final String STREAM_RETRY_ENV = "CODETUI_STREAM_RETRY";

    private static final Logger log = LoggerFactory.getLogger(StreamRetryConfig.class);
    private static final StreamRetryMode DEFAULT_MODE = StreamRetryMode.ALL;

    public StreamRetryConfig {
        Objects.requireNonNull(mode, "mode");
    }

    /** Reads the retry mode from the process environment. */
    public static StreamRetryConfig fromEnv() {
        return from(System::getenv);
    }

    /** Reads the retry mode through an injectable environment lookup for testability. */
    public static StreamRetryConfig from(Function<String, String> env) {
        Objects.requireNonNull(env, "env");
        return new StreamRetryConfig(resolveMode(env.apply(STREAM_RETRY_ENV)));
    }

    private static StreamRetryMode resolveMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MODE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "all" -> StreamRetryMode.ALL;
            case "l1" -> StreamRetryMode.L1_ONLY;
            case "l2" -> StreamRetryMode.L2_ONLY;
            case "off" -> StreamRetryMode.OFF;
            default -> {
                log.warn("Invalid {} value '{}'; falling back to all", STREAM_RETRY_ENV, raw);
                yield DEFAULT_MODE;
            }
        };
    }

    public boolean l1Enabled() {
        return mode == StreamRetryMode.ALL || mode == StreamRetryMode.L1_ONLY;
    }

    public boolean l2Enabled() {
        return mode == StreamRetryMode.ALL || mode == StreamRetryMode.L2_ONLY;
    }

    /** Retry layer selection exposed for assembly consumers. */
    public enum StreamRetryMode {
        ALL,
        L1_ONLY,
        L2_ONLY,
        OFF
    }
}
