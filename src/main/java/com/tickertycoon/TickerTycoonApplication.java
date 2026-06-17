package com.tickertycoon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class TickerTycoonApplication {
    public static void main(String[] args) {
        SpringApplication.run(TickerTycoonApplication.class, args);
    }
}
