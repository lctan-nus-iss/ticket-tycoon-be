package com.tickertycoon;

import com.tickertycoon.service.EventPoolService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties
public class TickerTycoonApplication {

    @Bean
    ApplicationRunner startEventPool(EventPoolService eventPoolService) {
        return args -> eventPoolService.startOnApplicationStartup();
    }

    public static void main(String[] args) {
        SpringApplication.run(TickerTycoonApplication.class, args);
    }
}
