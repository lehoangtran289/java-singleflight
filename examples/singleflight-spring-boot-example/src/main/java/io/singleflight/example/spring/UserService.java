package io.singleflight.example.spring;

import io.singleflight.core.SingleFlight;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;

@Service
public class UserService {

    private final SingleFlight<Long, User> users;
    private final UserRepository repository;

    public UserService(SingleFlight<Long, User> users, UserRepository repository) {
        this.users = users;
        this.repository = repository;
    }

    public User findById(long id) throws InterruptedException, ExecutionException {
        return users.execute(id, repository::findById);
    }

    public int repositoryLoadCount() {
        return repository.loadCount();
    }
}
