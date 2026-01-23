package com.signal.Signal.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.*;
import com.signal.Signal.dto.SignalResponse;
import com.signal.Signal.service.SignalBoardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class SignalSocketHandler extends TextWebSocketHandler {

    private final CopyOnWriteArrayList<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    private final ObjectMapper objectMapper;

    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private final Client geminiClient;

    private final SignalBoardService signalBoardService;

    @Value("${google.cloud.project-id}")
    private String projectId;

    private byte[] audioBuffer = new byte[0];
    private static final int BUFFER_THRESHOLD = 60000;


    public SignalSocketHandler(ObjectMapper objectMapper, Client geminiClient, SignalBoardService signalBoardService) {
        this.objectMapper = objectMapper;
        this.geminiClient = geminiClient;
        this.signalBoardService = signalBoardService;
    }


    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        log.info("🔌 Engineer connected: " + session.getId());

        SignalResponse welcome = SignalResponse.builder()
                .type(SignalResponse.SignalType.IDLE)
                .title("Signal Active")
                .description("Listening for high-impact meeting moments...")
                .timestamp(Instant.now())
                .confidence(1.0)
                .build();

        sendSignal(session, welcome);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("🔌 Engineer disconnected: " + session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {

        String payload = message.getPayload();
        log.info("Received command: " + payload);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        try {
            byte[] newChunk = message.getPayload().array();
            appendAudio(newChunk);

            if (audioBuffer.length > BUFFER_THRESHOLD) {
                byte[] audioToProcess = audioBuffer.clone();
                audioBuffer = new byte[0];
                executorService.submit(() -> processAudioWithGemini(session, audioToProcess));
            }
        } catch (Exception e) {
            log.error("Error handling binary audio", e);
        }
    }

    private void processAudioWithGemini(WebSocketSession session, byte[] audioData) {
        try {
            if (geminiClient == null) {
                log.warn("Gemini Client not initialized. Skipping.");
                return;
            }

            String systemText = """
            You are SIGNAL, a strict technical meeting analyst.
            
            CONTEXT: You are listening to a Software Engineering meeting.
            
            YOUR JOB:
            1. Filter out silence, background noise, or non-technical chatter.
            2. Only trigger if you hear explicit Engineering Intent:
               - DECISION_POINT: "We will use Postgres", "Let's merge this."
               - INPUT_REQUIRED: "What do you think?", "Any objections?"
               - RISK_DETECTED: "This will crash prod", "Latency is too high."
            
            CONSTRAINT:
            If the audio is unclear, silence, or not about software engineering -> Return { "type": "IDLE" }.
            DO NOT INVENT TEXT. DO NOT HALLUCINATE.

            Output JSON:
            {
              "type": "DECISION_POINT" | "INPUT_REQUIRED" | "RISK_DETECTED" | "IDLE",
              "title": "Short Headline",
              "description": "Specific details.",
              "suggestedResponse": "First-person professional response.",
              "confidence": 0.0 to 1.0
            }
            """;

            Content systemInstruction = Content.builder()
                    .parts(Collections.singletonList(Part.builder().text(systemText).build()))
                    .build();

            GenerateContentConfig config = GenerateContentConfig.builder()
                    .responseMimeType("application/json")
                    .systemInstruction(systemInstruction)
                    .temperature(0.2f)
                    .build();

            Content userContent = Content.builder()
                    .role("user")
                    .parts(Collections.singletonList(
                            Part.builder()
                                    .inlineData(Blob.builder()
                                            .mimeType("audio/webm")
                                            .data(audioData)
                                            .build())
                                    .build()
                    ))
                    .build();

            GenerateContentResponse response;

            try {
                // 1. Try Gemini 3 Pro
                response = geminiClient.models.generateContent(
                        "gemini-3-pro-preview",
                        userContent,
                        config
                );
            } catch (Exception e) {
                if (e.getMessage().contains("429") || e.getMessage().contains("Resource exhausted") || e.getMessage().contains("404")) {
                    log.warn("Gemini 3 Pro Issue (" + e.getMessage() + "). Switching to Flash...");
                    // 2. Fallback to Gemini 3 Flash
                    response = geminiClient.models.generateContent(
                            "gemini-3-flash-preview",
                            userContent,
                            config
                    );
                } else {
                    throw e;
                }
            }

            String resultText = response.text();
            if (resultText != null) {
                resultText = resultText.replace("```json", "").replace("```", "").trim();

                SignalResponse signal = objectMapper.readValue(resultText, SignalResponse.class);

                if (signal.getType() != SignalResponse.SignalType.IDLE) {
                    signal.setTimestamp(Instant.now());

                    // 1. Send the Text Signal first (Fast)
                    sendSignal(session, signal);

                    // 2. Check for "Architecture Board" Trigger (The Creative Autopilot)
                    if (signal.getType() == SignalResponse.SignalType.DECISION_POINT) {

                        String desc = signal.getDescription().toLowerCase();

                        // Trigger if the conversation is about structure/design
                        if (desc.contains("architecture") || desc.contains("design") ||
                                desc.contains("structure") || desc.contains("flow") || desc.contains("diagram")) {

                            log.info("Triggering Nano Banana Pro for: " + desc);
                            // Call the Image Service (Async)
                            signalBoardService.generateDiagram(session, signal.getDescription());
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Signal Processing Error: " + e.getMessage());
        }
    }

    private void appendAudio(byte[] newChunk) {
        byte[] combined = new byte[audioBuffer.length + newChunk.length];
        System.arraycopy(audioBuffer, 0, combined, 0, audioBuffer.length);
        System.arraycopy(newChunk, 0, combined, audioBuffer.length, newChunk.length);
        audioBuffer = combined;
    }

    public void sendSignal(WebSocketSession session, SignalResponse signal) {
        try {
            if (session.isOpen()) {
                String json = objectMapper.writeValueAsString(signal);
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.error("Error sending signal to frontend", e);
        }
    }
}