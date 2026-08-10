package io.singleflight.example.spring;

import io.singleflight.core.SingleFlight;
import io.singleflight.service.SingleFlightFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SingleFlightExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SingleFlightExampleApplication.class, args);
    }

    @Bean
    SingleFlight<User> userSingleFlight(SingleFlightFactory factory) {
        return factory.create();
    }
}
