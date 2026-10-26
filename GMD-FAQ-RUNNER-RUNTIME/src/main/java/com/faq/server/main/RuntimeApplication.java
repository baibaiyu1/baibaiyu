package com.gmd.faq.server.main;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class RuntimeApplication {
    public static void main( String[] args ) {
        SpringApplication.run(RuntimeApplication.class, args);
    }
}
