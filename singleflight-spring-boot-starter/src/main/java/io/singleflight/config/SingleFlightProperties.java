package io.singleflight.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the SingleFlight starter.
 */
@ConfigurationProperties(SingleFlightProperties.PREFIX)
public class SingleFlightProperties {

    public static final String PREFIX = "singleflight";

    /** Whether SingleFlight auto-configuration is enabled. */
    private boolean enabled = true;
    private final ExecutorProperties executor = new ExecutorProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ExecutorProperties getExecutor() {
        return executor;
    }

    public static class ExecutorProperties {

        /** Prefix assigned to worker thread names. */
        private String threadNamePrefix = "singleflight-";

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
                throw new IllegalArgumentException("singleflight.executor.thread-name-prefix must not be blank");
            }
            this.threadNamePrefix = threadNamePrefix;
        }
    }
}
