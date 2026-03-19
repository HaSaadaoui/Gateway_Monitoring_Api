package com.amaris.gatewaymonitoring;

import com.amaris.gatewaymonitoring.config.LorawanProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LorawanProperties.class)
public class GatewayMonitoringApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(GatewayMonitoringApiApplication.class, args);
	}

}
