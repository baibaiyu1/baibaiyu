package com.gmd.faq.server.main;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class ConsoleApplication {
    public static void main( String[] args ) {
        SpringApplication.run(ConsoleApplication.class, args);
    }
}
