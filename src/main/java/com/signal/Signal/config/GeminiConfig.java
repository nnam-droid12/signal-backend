package com.signal.Signal.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;

@Configuration
public class GeminiConfig {

    @Value("${google.cloud.project-id}")
    private String projectId;

    @Bean
    public Client geminiClient() throws IOException {
        String keyPath = "/etc/secrets/google-key.json";

        GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(keyPath))
                .createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));

        return Client.builder()
                .project(projectId)
                .location("global")
                .credentials(credentials)
                .vertexAI(true)
                .build();
    }
}