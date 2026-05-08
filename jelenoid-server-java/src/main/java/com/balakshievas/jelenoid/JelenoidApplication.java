package com.balakshievas.jelenoid;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JelenoidApplication {
	public static void main(String[] args) {
		SpringApplication.run(JelenoidApplication.class, args);
	}
}
