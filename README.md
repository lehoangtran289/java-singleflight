# Java SingleFlight

Java SingleFlight coalesces concurrent operations that use the same key. While one caller loads
`user:123`, later callers join that in-flight operation and receive its value or exception. The
result is removed immediately after completion, so this is not a cache or a distributed lock.

## Modules

| Module                             | Purpose                                                |
|------------------------------------|--------------------------------------------------------|
| `singleflight-core`                | Framework-free Java 25 API and implementation          |
| `singleflight-spring-boot-starter` | Spring Boot auto-configuration and typed-group factory |
| `examples/singleflight-java-example` | Runnable framework-free coalescing demonstration     |
| `examples/singleflight-spring-boot-example` | Runnable Spring Boot REST demonstration       |

Runnable build and usage instructions are available in [`examples/README.md`](examples/README.md).

## Usage

Add the starter to a Spring Boot 4.1 application:

```xml
<dependency>
    <groupId>io.singleflight</groupId>
    <artifactId>singleflight-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The starter automatically creates:

- a `SingleFlight<Object>` bean named `singleFlight`;
- a `SingleFlightFactory` for creating type-safe, independent groups;
- an executor named `singleFlightExecutor` for asynchronous suppliers.

Configure the managed executor in `application.yaml`:

```yaml
singleflight:
  enabled: true
  executor:
    thread-name-prefix: singleflight-
```

For type-safe application beans, create a group from the factory:

```java
import io.singleflight.core.SingleFlight;
import io.singleflight.service.SingleFlightFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SingleFlightConfiguration {

    @Bean
    SingleFlight<User> userSingleFlight(SingleFlightFactory factory) {
        return factory.create();
    }
}
```

Inject and use it like any other Spring bean:

```java
import io.singleflight.core.SingleFlight;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;

@Service
class UserService {

    private final SingleFlight<User> userSingleFlight;
    private final UserRepository repository;

    UserService(SingleFlight<User> userSingleFlight, UserRepository repository) {
        this.userSingleFlight = userSingleFlight;
        this.repository = repository;
    }

    User findById(long id) throws InterruptedException, ExecutionException {
        return userSingleFlight.execute("user:" + id, () -> repository.findById(id));
    }
}
```

Concurrent calls with the same key share one supplier execution. Different keys can execute in
parallel, and completed calls are removed immediately.

### Overrides

 To replace the
managed executor, provide a bean with its expected name:

```java
@Bean(name = "singleFlightExecutor", destroyMethod = "close")
ExecutorService singleFlightExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
}
```

Set `singleflight.enabled=false` to disable all starter auto-configuration.

### Logging

The core module uses `java.util.logging`, so it adds no logging dependency. `execute`/`executeAsync`
propagate the supplier's exception (or, for a synchronous follower, an `InterruptedException` if
its thread is interrupted while waiting) directly to the caller rather than logging it. Only a
rejected asynchronous submission — an internal executor failure unrelated to the supplier — is
logged, at `SEVERE`.

Spring Boot applications can enable the lifecycle records with:

```yaml
logging:
  level:
    io.singleflight: DEBUG
```
