package com.signal.Signal.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.*;
import com.signal.Signal.service.SignalResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.FileInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class SignalSocketHandler extends TextWebSocketHandler {

    private final CopyOnWriteArrayList<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private Client geminiClient;

    @Value("${google.cloud.project-id}")
    private String projectId;


    private byte[] audioBuffer = new byte[0];
    private static final int BUFFER_THRESHOLD = 60000;

    public SignalSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        try {
            String keyPath = "/etc/secrets/google-key.json";

            GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(keyPath))
                    .createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));

            this.geminiClient = Client.builder()
                    .project(projectId)
                    .location("us-central1")
                    .credentials(credentials)
                    .vertexAI(true)
                    .build();

            log.info("Gemini 3 Client Initialized Successfully");
        } catch (Exception e) {
            log.error("Failed to initialize Gemini Client: " + e.getMessage());
        }
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
            if (projectId == null) projectId = "your-default-project-id";

            String systemText = """
                You are SIGNAL, a real-time accessibility co-pilot.
                Analyze the audio. Detect: DECISION_POINT, INPUT_REQUIRED, RISK_DETECTED.
                Output JSON only: { "type": "...", "title": "...", "description": "...", "suggestedResponse": "..." }
                If nothing important, return { "type": "IDLE" }.
                """;

            Content systemInstruction = Content.builder()
                    .parts(Collections.singletonList(
                            Part.builder().text(systemText).build()
                    ))
                    .build();

            GenerateContentConfig config = GenerateContentConfig.builder()
                    .responseMimeType("application/json")
                    .systemInstruction(systemInstruction)
                    .temperature(0.4f)
                    .build();

            Content userContent = Content.builder()
                    .role("user")
                    .parts(Collections.singletonList(
                            Part.builder()
                                    .inlineData(Blob.builder()
                                            .mimeType("audio/wav")
                                            .data(audioData)
                                            .build())
                                    .build()
                    ))
                    .build();


            GenerateContentResponse response = geminiClient.models.generateContent(
                    "gemini-3.0-pro-preview",
                    userContent,
                    config
            );


            String resultText = response.text();
            if (resultText != null) {

                resultText = resultText.replace("```json", "").replace("```", "").trim();

                SignalResponse signal = objectMapper.readValue(resultText, SignalResponse.class);

                if (signal.getType() != SignalResponse.SignalType.IDLE) {
                    signal.setTimestamp(Instant.now());
                    sendSignal(session, signal);
                }
            }

        } catch (Exception e) {
            log.error("Error in Gemini Processing: " + e.getMessage());
            e.printStackTrace();
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