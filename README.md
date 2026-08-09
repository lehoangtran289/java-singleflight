# Java SingleFlight

Java SingleFlight coalesces concurrent operations that use the same key. While one caller loads
`user:123`, later callers join that in-flight operation and receive its value or exception. The
result is removed immediately after completion, so this is not a cache or a distributed lock.

## Modules

| Module                             | Purpose                                                |
|------------------------------------|--------------------------------------------------------|
| `singleflight-core`                | Framework-free Java 25 API and implementation          |
| `singleflight-spring-boot-starter` | Spring Boot auto-configuration and typed-group factory |

## Usage
