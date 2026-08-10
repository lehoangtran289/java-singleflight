package io.singleflight.example.spring;

import org.springframework.stereotype.Repository;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Repository
class UserRepository {

    private final AtomicInteger loadCount = new AtomicInteger();

    User findById(long id) {
        int repositoryLoad = loadCount.incrementAndGet();
        System.out.printf("Loading user %d from the repository (load #%d)%n", id, repositoryLoad);
        sleep(500);
        return new User(id, "user-" + id, repositoryLoad);
    }

    int loadCount() {
        return loadCount.get();
    }

    private static void sleep(long milliseconds) {
        try {
            TimeUnit.MILLISECONDS.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Repository load was interrupted", exception);
        }
    }
}
