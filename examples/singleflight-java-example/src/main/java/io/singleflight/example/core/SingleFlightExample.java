package io.singleflight.example.core;

import io.singleflight.core.DefaultSingleFlight;
import io.singleflight.core.SingleFlight;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class SingleFlightExample {

    private static final String USER_KEY = "user:42";

    private SingleFlightExample() {
    }

    public static void main(String[] args) throws InterruptedException {
        AtomicInteger repositoryLoads = new AtomicInteger();
        CountDownLatch repositoryStarted = new CountDownLatch(1);
        CountDownLatch releaseRepository = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SingleFlight<User> users = new DefaultSingleFlight<>(executor);
            Supplier<User> loadUser = () -> {
                int loadNumber = repositoryLoads.incrementAndGet();
                System.out.printf("Repository load #%d on %s%n", loadNumber, Thread.currentThread());
                repositoryStarted.countDown();
                await(releaseRepository);
                return new User(42, "Ada Lovelace");
            };

            List<CompletableFuture<User>> requests = new ArrayList<>();
            requests.add(users.executeAsync(USER_KEY, loadUser));
            repositoryStarted.await();

            for (int caller = 2; caller <= 5; caller++) {
                requests.add(users.executeAsync(USER_KEY, loadUser));
            }

            System.out.println("Five callers are sharing the same in-flight key.");
            releaseRepository.countDown();
            CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();

            for (int caller = 0; caller < requests.size(); caller++) {
                System.out.printf("Caller %d received %s%n", caller + 1, requests.get(caller).join());
            }
            System.out.printf("Repository loads: %d (expected 1)%n", repositoryLoads.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Repository load was interrupted", exception);
        }
    }

    private record User(long id, String name) {
    }
}
