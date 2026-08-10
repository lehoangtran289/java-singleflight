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

        /** Number of threads used to execute asynchronous suppliers for independent keys. */
        private int poolSize = Math.max(2, Runtime.getRuntime().availableProcessors());

        /** Prefix assigned to worker thread names. */
        private String threadNamePrefix = "singleflight-";

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            if (poolSize < 1) {
                throw new IllegalArgumentException("singleflight.executor.pool-size must be at least 1");
            }
            this.poolSize = poolSize;
        }

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
