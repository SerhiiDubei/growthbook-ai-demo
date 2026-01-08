package com.example.gb.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

  @Bean(name = "gbAdminClient")
  public WebClient gbAdminClient(WebClient.Builder builder,
                                 @Value("${growthbook.admin.base-url}") String baseUrl,
                                 @Value("${growthbook.admin.token}") String token) {
    return builder
        .baseUrl(baseUrl)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .build();
  }

  @Bean
  public WebClient.Builder webClientBuilder() {
    return WebClient.builder();
  }
}
