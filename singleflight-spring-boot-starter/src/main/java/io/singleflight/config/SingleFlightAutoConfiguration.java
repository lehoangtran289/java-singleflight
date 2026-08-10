package io.singleflight.config;

import io.singleflight.core.SingleFlight;
import io.singleflight.service.SingleFlightFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@AutoConfiguration
@ConditionalOnClass(SingleFlight.class)
@ConditionalOnBooleanProperty(prefix = SingleFlightProperties.PREFIX, name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(SingleFlightProperties.class)
public class SingleFlightAutoConfiguration {

    static final String EXECUTOR_BEAN_NAME = "singleFlightExecutor";

    @Bean(name = EXECUTOR_BEAN_NAME, destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = EXECUTOR_BEAN_NAME)
    public ThreadPoolExecutor singleFlightExecutor(SingleFlightProperties properties) {
        SingleFlightProperties.ExecutorProperties executorProperties = properties.getExecutor();
        int poolSize = executorProperties.getPoolSize();
        ThreadFactory threadFactory = Thread.ofPlatform()
                .name(executorProperties.getThreadNamePrefix(), 0)
                .factory();
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                threadFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public SingleFlightFactory singleFlightFactory(
            @Qualifier(EXECUTOR_BEAN_NAME) Executor executor) {
        return new SingleFlightFactory(executor);
    }

    @Bean
    @ConditionalOnMissingBean(SingleFlight.class)
    public SingleFlight<Object> singleFlight(SingleFlightFactory factory) {
        return factory.create();
    }
}
