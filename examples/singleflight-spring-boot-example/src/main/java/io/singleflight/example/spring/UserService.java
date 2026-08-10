package io.singleflight.example.spring;

import io.singleflight.core.SingleFlight;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;

@Service
class UserService {

    private final SingleFlight<User> users;
    private final UserRepository repository;

    UserService(SingleFlight<User> users, UserRepository repository) {
        this.users = users;
        this.repository = repository;
    }

    User findById(long id) throws InterruptedException, ExecutionException {
        return users.execute("user:" + id, () -> repository.findById(id));
    }

    int repositoryLoadCount() {
        return repository.loadCount();
    }
}
