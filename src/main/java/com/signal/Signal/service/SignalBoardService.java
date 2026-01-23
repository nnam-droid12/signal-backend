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
        try {
            log.info("Asking Nano Banana Pro to visualize architecture...");

            String visualPrompt = """
                You are an expert software architect.
                Based on this conversation: "%s"
                
                Generate a professional high-fidelity cloud architecture diagram.
                - Style: Whiteboard technical diagram, clean lines, legible text.
                - Components: Include accurate icons for the services mentioned.
                - Detail: High resolution, optimized for readability.
            """.formatted(conversationContext);

            GenerateContentConfig config = GenerateContentConfig.builder()
                    .responseModalities(Arrays.asList("TEXT", "IMAGE"))
                    .temperature(0.5f)
                    .build();


            GenerateContentResponse response = geminiClient.models.generateContent(
                    "gemini-3-pro-image-preview",
                    Content.builder().parts(Collections.singletonList(
                            Part.builder().text(visualPrompt).build()
                    )).build(),
                    config
            );

            if (response.parts() != null) {
                for (Part part : response.parts()) {

                    if (part.inlineData().isPresent()) {
                        Blob blob = part.inlineData().get();

                        if (blob.data().isPresent()) {
                            byte[] imageBytes = blob.data().get();

                            if (imageBytes != null && imageBytes.length > 0) {
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
                                log.info("Nano Banana Pro Diagram Sent!");
                                return;
                            }
                        }
                    }
                }
            }
            log.warn("No image found in Gemini response.");

        } catch (Exception e) {
            log.error("Failed to generate diagram with Nano Banana Pro", e);
        }
    }
}