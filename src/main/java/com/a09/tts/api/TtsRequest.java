package com.a09.tts.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TtsRequest(
        @NotBlank @Size(max = 5000) String text,
        @NotBlank @Size(max = 100) String voice,
        @DecimalMin("0.5") @DecimalMax("2.0") Double speed,
        @DecimalMin("0.5") @DecimalMax("2.0") Double pitch,
        @DecimalMin("0.5") @DecimalMax("2.0") Double rhythm
) {
    public double effectiveSpeed() {
        return speed == null ? 1.0 : speed;
    }

    public double effectivePitch() {
        return pitch == null ? 1.0 : pitch;
    }

    public double effectiveRhythm() {
        return rhythm == null ? 1.0 : rhythm;
    }
}
