# SingleFlight Examples

Both examples target Java 25 and are built by the root Maven reactor.

## Framework-free Java

The Java example starts five asynchronous callers for `user:42`. They all receive the same value
while the simulated repository runs once.

```bash
mvn -pl examples/singleflight-java-example -am package
java -jar examples/singleflight-java-example/target/singleflight-java-example-0.1.0-SNAPSHOT.jar
```

The final line should be `Repository loads: 1 (expected 1)`.

## Spring Boot

The Spring Boot example creates a typed `SingleFlight<User>` group and exposes a slow user lookup
through a REST endpoint.

```bash
mvn -pl examples/singleflight-spring-boot-example -am package
java -jar examples/singleflight-spring-boot-example/target/singleflight-spring-boot-example-0.1.0-SNAPSHOT.jar
```

While the application is running, send several requests for the same user at once:

```bash
for caller in {1..5}; do
  curl --silent http://localhost:8080/users/42 &
done
wait

curl --silent http://localhost:8080/metrics/repository-loads
```

All five responses report the same `repositoryLoad`, and the metrics endpoint reports one
repository load. A later request starts a new load because SingleFlight coalesces only concurrent
work; it is not a cache.
