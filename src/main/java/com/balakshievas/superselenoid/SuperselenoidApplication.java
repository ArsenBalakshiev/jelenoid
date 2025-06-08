package com.balakshievas.superselenoid;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SuperselenoidApplication {
	public static void main(String[] args) {
		SpringApplication.run(SuperselenoidApplication.class, args);
	}
}
