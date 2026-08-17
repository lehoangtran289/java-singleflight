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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

@AutoConfiguration
@ConditionalOnClass(SingleFlight.class)
@ConditionalOnBooleanProperty(prefix = SingleFlightProperties.PREFIX, name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(SingleFlightProperties.class)
public class SingleFlightAutoConfiguration {

    static final String EXECUTOR_BEAN_NAME = "singleFlightExecutor";

    @Bean(name = EXECUTOR_BEAN_NAME, destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = EXECUTOR_BEAN_NAME)
    public ExecutorService singleFlightExecutor(SingleFlightProperties properties) {
        SingleFlightProperties.ExecutorProperties executorProperties = properties.getExecutor();
        ThreadFactory threadFactory = Thread.ofVirtual()
                .name(executorProperties.getThreadNamePrefix(), 0)
                .factory();
        return Executors.newThreadPerTaskExecutor(threadFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public SingleFlightFactory singleFlightFactory(
            @Qualifier(EXECUTOR_BEAN_NAME) Executor executor) {
        return new SingleFlightFactory(executor);
    }

    @Bean
    @ConditionalOnMissingBean(SingleFlight.class)
    public SingleFlight<Object, Object> singleFlight(SingleFlightFactory factory) {
        return factory.create();
    }
}
