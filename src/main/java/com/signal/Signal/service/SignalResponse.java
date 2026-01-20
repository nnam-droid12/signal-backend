package com.signal.Signal.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalResponse {
    private SignalType type;

    private String title;

    private String description;

    private String suggestedResponse;

    private double confidence;

    private Instant timestamp;

    public enum SignalType {
        DECISION_POINT,
        INPUT_REQUIRED,
        RISK_DETECTED,
        CONTRADICTION,
        IDLE
    }
}