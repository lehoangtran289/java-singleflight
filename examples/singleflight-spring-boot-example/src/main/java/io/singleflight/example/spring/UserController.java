package io.singleflight.example.spring;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ExecutionException;

@RestController
class UserController {

    private final UserService users;

    UserController(UserService users) {
        this.users = users;
    }

    @GetMapping("/users/{id}")
    User findById(@PathVariable("id") long id) throws InterruptedException, ExecutionException {
        return users.findById(id);
    }

    @GetMapping("/metrics/repository-loads")
    Map<String, Integer> repositoryLoads() {
        return Map.of("repositoryLoads", users.repositoryLoadCount());
    }
}
