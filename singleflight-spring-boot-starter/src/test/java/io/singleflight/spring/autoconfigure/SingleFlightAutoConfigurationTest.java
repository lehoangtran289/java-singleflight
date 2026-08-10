package io.singleflight.spring.autoconfigure;

import io.singleflight.config.SingleFlightAutoConfiguration;
import io.singleflight.config.SingleFlightProperties;
import io.singleflight.core.DefaultSingleFlight;
import io.singleflight.core.SingleFlight;
import io.singleflight.service.SingleFlightFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

class SingleFlightAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SingleFlightAutoConfiguration.class));

    @Test
    void createsDefaultSingleFlightInfrastructure() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SingleFlightProperties.class);
            assertThat(context).hasSingleBean(SingleFlightFactory.class);
            assertThat(context).hasSingleBean(SingleFlight.class);
            assertThat(context).hasSingleBean(ThreadPoolExecutor.class);
            assertThat(context).hasBean("singleFlightExecutor");
        });
    }

    @Test
    void bindsExecutorConfiguration() {
        contextRunner
                .withPropertyValues(
                        "singleflight.executor.pool-size=3",
                        "singleflight.executor.thread-name-prefix=configured-flight-")
                .run(context -> {
                    SingleFlightProperties properties = context.getBean(SingleFlightProperties.class);
                    ThreadPoolExecutor executor = context.getBean("singleFlightExecutor", ThreadPoolExecutor.class);
                    CompletableFuture<String> threadName = new CompletableFuture<>();

                    executor.execute(() -> threadName.complete(Thread.currentThread().getName()));

                    assertThat(properties.getExecutor().getPoolSize()).isEqualTo(3);
                    assertThat(executor.getCorePoolSize()).isEqualTo(3);
                    assertThat(executor.getMaximumPoolSize()).isEqualTo(3);
                    assertThat(threadName.get(1, SECONDS)).startsWith("configured-flight-");
                });
    }

    @Test
    void canDisableAutoConfiguration() {
        contextRunner
                .withPropertyValues("singleflight.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SingleFlightProperties.class);
                    assertThat(context).doesNotHaveBean(SingleFlightFactory.class);
                    assertThat(context).doesNotHaveBean(SingleFlight.class);
                    assertThat(context).doesNotHaveBean("singleFlightExecutor");
                });
    }

    @Test
    void rejectsNonPositivePoolSize() {
        contextRunner
                .withPropertyValues("singleflight.executor.pool-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shutsDownManagedExecutorWithApplicationContext() {
        AtomicReference<ThreadPoolExecutor> executorReference = new AtomicReference<>();

        contextRunner.run(context -> executorReference.set(
                context.getBean("singleFlightExecutor", ThreadPoolExecutor.class)));

        assertThat(executorReference.get()).isNotNull().matches(ThreadPoolExecutor::isShutdown);
    }

    @Test
    void usesConsumerProvidedExecutor() {
        Executor directExecutor = Runnable::run;

        contextRunner
                .withBean("singleFlightExecutor", Executor.class, () -> directExecutor)
                .run(context -> {
                    SingleFlight<String> singleFlight = context.getBean(SingleFlightFactory.class).create();

                    assertThat(context.getBean("singleFlightExecutor")).isSameAs(directExecutor);
                    assertThat(singleFlight.executeAsync("key", () -> "value").join()).isEqualTo("value");
                });
    }

    @Test
    void backsOffWhenConsumerProvidesSingleFlightBean() {
        DefaultSingleFlight<Object> customSingleFlight = new DefaultSingleFlight<>(Runnable::run);

        contextRunner
                .withBean("customSingleFlight", DefaultSingleFlight.class, () -> customSingleFlight)
                .run(context -> {
                    assertThat(context).hasSingleBean(SingleFlight.class);
                    assertThat(context.getBean(SingleFlight.class)).isSameAs(customSingleFlight);
                });
    }

    @Test
    void factoryCreatesTypedSingleFlightThatCoalescesCalls() {
        contextRunner
                .withPropertyValues("singleflight.executor.pool-size=2")
                .run(context -> {
                    SingleFlight<String> singleFlight = context.getBean(SingleFlightFactory.class).create();
                    CountDownLatch supplierStarted = new CountDownLatch(1);
                    CountDownLatch releaseSupplier = new CountDownLatch(1);
                    AtomicInteger invocationCount = new AtomicInteger();

                    CompletableFuture<String> first = singleFlight.executeAsync("user:123", () -> {
                        invocationCount.incrementAndGet();
                        supplierStarted.countDown();
                        await(releaseSupplier);
                        return "user";
                    });
                    assertThat(supplierStarted.await(1, SECONDS)).isTrue();
                    CompletableFuture<String> second = singleFlight.executeAsync(
                            "user:123", () -> "unexpected");

                    releaseSupplier.countDown();

                    assertThat(List.of(first.join(), second.join()))
                            .containsExactly("user", "user");
                    assertThat(invocationCount).hasValue(1);
                });
    }

    @Test
    void discoversAutoConfigurationFromStarterMetadata() {
        new ApplicationContextRunner()
                .withUserConfiguration(AutoConfigurationEnabledApplication.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(SingleFlightFactory.class);
                    assertThat(context).hasSingleBean(SingleFlight.class);
                });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, SECONDS)) {
                throw new AssertionError("Timed out waiting for test coordination latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test coordination latch", exception);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class AutoConfigurationEnabledApplication {
    }
}
