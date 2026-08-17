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
import java.util.concurrent.ExecutorService;
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
            assertThat(context).hasSingleBean(ExecutorService.class);
            assertThat(context).hasBean("singleFlightExecutor");
        });
    }

    @Test
    void bindsExecutorConfiguration() {
        contextRunner
                .withPropertyValues("singleflight.executor.thread-name-prefix=configured-flight-")
                .run(context -> {
                    SingleFlightProperties properties = context.getBean(SingleFlightProperties.class);
                    ExecutorService executor = context.getBean("singleFlightExecutor", ExecutorService.class);
                    CompletableFuture<String> threadName = new CompletableFuture<>();
                    CompletableFuture<Boolean> isVirtual = new CompletableFuture<>();

                    executor.execute(() -> {
                        threadName.complete(Thread.currentThread().getName());
                        isVirtual.complete(Thread.currentThread().isVirtual());
                    });

                    assertThat(properties.getExecutor().getThreadNamePrefix()).isEqualTo("configured-flight-");
                    assertThat(threadName.get(1, SECONDS)).startsWith("configured-flight-");
                    assertThat(isVirtual.get(1, SECONDS)).isTrue();
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
    void shutsDownManagedExecutorWithApplicationContext() {
        AtomicReference<ExecutorService> executorReference = new AtomicReference<>();

        contextRunner.run(context -> executorReference.set(
                context.getBean("singleFlightExecutor", ExecutorService.class)));

        assertThat(executorReference.get()).isNotNull().matches(ExecutorService::isShutdown);
    }

    @Test
    void usesConsumerProvidedExecutor() {
        Executor directExecutor = Runnable::run;

        contextRunner
                .withBean("singleFlightExecutor", Executor.class, () -> directExecutor)
                .run(context -> {
                    SingleFlight<String, String> singleFlight = context.getBean(SingleFlightFactory.class).create();

                    assertThat(context.getBean("singleFlightExecutor")).isSameAs(directExecutor);
                    assertThat(singleFlight.executeAsync("key", key -> "value").join()).isEqualTo("value");
                });
    }

    @Test
    void backsOffWhenConsumerProvidesSingleFlightBean() {
        DefaultSingleFlight<Object, Object> customSingleFlight = new DefaultSingleFlight<>(Runnable::run);

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
                .run(context -> {
                    SingleFlight<String, String> singleFlight = context.getBean(SingleFlightFactory.class).create();
                    CountDownLatch supplierStarted = new CountDownLatch(1);
                    CountDownLatch releaseSupplier = new CountDownLatch(1);
                    AtomicInteger invocationCount = new AtomicInteger();

                    CompletableFuture<String> first = singleFlight.executeAsync("user:123", key -> {
                        invocationCount.incrementAndGet();
                        supplierStarted.countDown();
                        await(releaseSupplier);
                        return "user";
                    });
                    assertThat(supplierStarted.await(1, SECONDS)).isTrue();
                    CompletableFuture<String> second = singleFlight.executeAsync(
                            "user:123", key -> "unexpected");

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
