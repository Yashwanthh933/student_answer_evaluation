package com.answerscripts.admin;

public record ModelDefinition(
        String id,
        String provider,
        String modelName,
        String endpoint,
        String apiKeyEnv,
        boolean canReadImages) {
}
