package com.solidbrain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot application entry class
 */
@SpringBootApplication
@EnableScheduling
@SuppressWarnings("squid:S1118")
public class SelfTrainingApplication {

    /**
     * Spring Boot application entry point method
     * @param args arguments (currently not used)
     */
    // https://jira.sonarsource.com/browse/SONARJAVA-1687
	@SuppressWarnings("squid:S2095")
	public static void main(String[] args) {
		SpringApplication.run(SelfTrainingApplication.class, args);
	}
}
