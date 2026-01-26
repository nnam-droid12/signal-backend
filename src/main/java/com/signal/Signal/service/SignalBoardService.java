package com.signal.Signal.service;

import com.google.genai.Client;
import com.google.genai.types.*;
import com.signal.Signal.dto.SignalResponse;
import com.signal.Signal.websocket.SignalSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignalBoardService {

    private final Client geminiClient;

    @Lazy
    private final SignalSocketHandler socketHandler;

    @Async
    public void generateDiagram(WebSocketSession session, String conversationContext) {
        int maxRetries = 3;
        int attempt = 0;
        boolean success = false;

        String visualPrompt = """
            Create a high-fidelity software architecture diagram based on this:
            "%s"
            
            Requirements:
            - Whiteboard style with clear icons (Postgres, Redis, API Gateway).
            - Draw arrows showing the flow from Gateway to Services.
            - High resolution output.
        """.formatted(conversationContext);

        GenerateContentConfig config = GenerateContentConfig.builder()
                .responseModalities(Arrays.asList("TEXT", "IMAGE"))
                .temperature(0.5f)
                .build();


        while (attempt < maxRetries && !success) {
            try {
                attempt++;
                log.info("Asking Nano Banana Pro (Attempt " + attempt + "/" + maxRetries + ")...");

                GenerateContentResponse response = geminiClient.models.generateContent(
                        "gemini-3-pro-image-preview",
                        Content.builder()
                                .role("user")
                                .parts(Collections.singletonList(
                                        Part.builder().text(visualPrompt).build()
                                )).build(),
                        config
                );

                List<Candidate> candidates = response.candidates().orElse(Collections.emptyList());

                for (Candidate candidate : candidates) {
                    if (candidate.content().isPresent()) {
                        Content content = candidate.content().get();
                        List<Part> parts = content.parts().orElse(Collections.emptyList());

                        for (Part part : parts) {
                            if (part.inlineData().isPresent()) {
                                Blob blob = part.inlineData().get();
                                if (blob.data().isPresent()) {
                                    byte[] imageBytes = blob.data().get();

                                    String base64Image = Base64.getEncoder().encodeToString(imageBytes);

                                    SignalResponse visualSignal = SignalResponse.builder()
                                            .type(SignalResponse.SignalType.IMAGE_GENERATED)
                                            .title("Live Architecture Board")
                                            .description("Gemini 3 generated this diagram from the discussion.")
                                            .imageBase64(base64Image)
                                            .timestamp(Instant.now())
                                            .confidence(1.0)
                                            .build();

                                    socketHandler.sendSignal(session, visualSignal);
                                    log.info("Diagram Sent to Frontend!");
                                    success = true;
                                    return;
                                }
                            }
                        }
                    }
                }

                if (!success) {
                    log.warn("Request succeeded but no image data found.");
                    success = true;
                }

            } catch (Exception e) {

                if (e.getMessage().contains("429") || e.getMessage().contains("Resource exhausted")) {
                    log.warn("Quota Limit (429). Waiting to retry...");
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    log.error("Critical Error (Not Quota): " + e.getMessage());
                    break;
                }
            }
        }

        if (!success) {
            log.error("Failed to generate diagram after " + maxRetries + " attempts.");
        }
    }
}