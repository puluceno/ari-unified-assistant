package io.github.puluceno.ari;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot entry point. */
@SpringBootApplication
public class AriApplication {

    /** Starts the hub on port 8080. */
    public static void main(String[] args) {
        SpringApplication.run(AriApplication.class, args);
    }
}
