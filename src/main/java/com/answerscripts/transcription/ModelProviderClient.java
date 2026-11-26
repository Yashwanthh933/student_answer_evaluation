package com.answerscripts.transcription;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.answerscripts.admin.ModelDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
class ModelProviderClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final ObjectMapper objectMapper;

    ModelProviderClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String readPages(ModelDefinition model, String apiKey, String instructions, List<byte[]> pages)
            throws InterruptedException {
        String provider = model.provider().toLowerCase();
        Map<String, Object> body = switch (provider) {
            case "openai" -> openAiBody(model, instructions, pages);
            case "google" -> googleBody(instructions, pages);
            case "anthropic" -> anthropicBody(model, instructions, pages);
            default -> throw new ModelCallException("The selected model provider is not supported.");
        };

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(model.endpoint()))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json");
        switch (provider) {
            case "openai" -> request.header("Authorization", "Bearer " + apiKey);
            case "google" -> request.header("x-goog-api-key", apiKey);
            case "anthropic" -> request.header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01");
            default -> throw new ModelCallException("The selected model provider is not supported.");
        }

        HttpResponse<String> response;
        try {
            request.POST(HttpRequest.BodyPublishers.ofString(toJson(body)));
            response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new ModelCallException("Could not contact the selected model provider. Check the network and try again.");
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ModelCallException(providerError(model.provider(), response.statusCode()));
        }
        return responseText(provider, response.body());
    }

    String markAnswers(
            ModelDefinition model,
            String apiKey,
            String instructions,
            String studentAnswers,
            List<byte[]> questionPaperPages) throws InterruptedException {
        return markAnswers(model, apiKey, instructions, null, studentAnswers, questionPaperPages);
    }

    String markAnswers(
            ModelDefinition model,
            String apiKey,
            String instructions,
            String rubricText,
            String studentAnswers,
            List<byte[]> questionPaperPages) throws InterruptedException {
        String provider = model.provider().toLowerCase();
        Map<String, Object> body = switch (provider) {
            case "openai" -> openAiMarkingBody(model, instructions, rubricText, studentAnswers, questionPaperPages);
            case "google" -> googleMarkingBody(instructions, rubricText, studentAnswers, questionPaperPages);
            case "anthropic" -> anthropicMarkingBody(model, instructions, rubricText, studentAnswers, questionPaperPages);
            default -> throw new ModelCallException("The selected model provider is not supported.");
        };

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(model.endpoint()))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json");
        switch (provider) {
            case "openai" -> request.header("Authorization", "Bearer " + apiKey);
            case "google" -> request.header("x-goog-api-key", apiKey);
            case "anthropic" -> request.header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01");
            default -> throw new ModelCallException("The selected model provider is not supported.");
        }

        HttpResponse<String> response;
        try {
            request.POST(HttpRequest.BodyPublishers.ofString(toJson(body)));
            response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new ModelCallException("Could not contact the selected model provider. Check the network and try again.");
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ModelCallException(providerError(model.provider(), response.statusCode()));
        }
        return responseText(provider, response.body());
    }

    private Map<String, Object> openAiBody(ModelDefinition model, String instructions, List<byte[]> pages) {
        List<Map<String, Object>> content = new ArrayList<>();
        for (byte[] page : pages) {
            String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(page);
            content.add(Map.of("type", "input_image", "image_url", dataUrl));
        }
        Map<String, Object> message = Map.of("role", "user", "content", content);
        return Map.of("model", model.modelName(), "instructions", instructions, "input", List.of(message));
    }

    private Map<String, Object> googleBody(String instructions, List<byte[]> pages) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", instructions));
        for (byte[] page : pages) {
            parts.add(Map.of("inline_data", Map.of(
                    "mime_type", "image/jpeg",
                    "data", Base64.getEncoder().encodeToString(page))));
        }
        return Map.of("contents", List.of(Map.of("role", "user", "parts", parts)));
    }

    private Map<String, Object> anthropicBody(ModelDefinition model, String instructions, List<byte[]> pages) {
        List<Map<String, Object>> content = new ArrayList<>();
        for (byte[] page : pages) {
            content.add(Map.of("type", "image", "source", Map.of(
                    "type", "base64",
                    "media_type", "image/jpeg",
                    "data", Base64.getEncoder().encodeToString(page))));
        }
        return Map.of(
                "model", model.modelName(),
                "max_tokens", 8192,
                "system", instructions,
                "messages", List.of(Map.of("role", "user", "content", content)));
    }

    private Map<String, Object> openAiMarkingBody(
            ModelDefinition model,
            String instructions,
            String rubricText,
            String studentAnswers,
            List<byte[]> pages) {
        List<Map<String, Object>> content = new ArrayList<>();
        if (rubricText != null) {
            content.add(Map.of("type", "input_text", "text", "Marking rubric:\n" + rubricText));
        }
        content.add(Map.of("type", "input_text", "text", "Student answers:\n" + studentAnswers));
        for (byte[] page : pages) {
            String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(page);
            content.add(Map.of("type", "input_image", "image_url", dataUrl));
        }
        Map<String, Object> message = Map.of("role", "user", "content", content);
        return Map.of("model", model.modelName(), "instructions", instructions, "input", List.of(message));
    }

    private Map<String, Object> googleMarkingBody(
            String instructions, String rubricText, String studentAnswers, List<byte[]> pages) {
        List<Map<String, Object>> parts = new ArrayList<>();
        String requestText = instructions;
        if (rubricText != null) {
            requestText += "\n\nMarking rubric:\n" + rubricText;
        }
        requestText += "\n\nStudent answers:\n" + studentAnswers;
        parts.add(Map.of("text", requestText));
        for (byte[] page : pages) {
            parts.add(Map.of("inline_data", Map.of(
                    "mime_type", "image/jpeg",
                    "data", Base64.getEncoder().encodeToString(page))));
        }
        return Map.of("contents", List.of(Map.of("role", "user", "parts", parts)));
    }

    private Map<String, Object> anthropicMarkingBody(
            ModelDefinition model,
            String instructions,
            String rubricText,
            String studentAnswers,
            List<byte[]> pages) {
        List<Map<String, Object>> content = new ArrayList<>();
        if (rubricText != null) {
            content.add(Map.of("type", "text", "text", "Marking rubric:\n" + rubricText));
        }
        content.add(Map.of("type", "text", "text", "Student answers:\n" + studentAnswers));
        for (byte[] page : pages) {
            content.add(Map.of("type", "image", "source", Map.of(
                    "type", "base64",
                    "media_type", "image/jpeg",
                    "data", Base64.getEncoder().encodeToString(page))));
        }
        return Map.of(
                "model", model.modelName(),
                "max_tokens", 8192,
                "system", instructions,
                "messages", List.of(Map.of("role", "user", "content", content)));
    }

    private String responseText(String provider, String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (provider.equals("openai")) {
                String outputText = root.path("output_text").asText("");
                if (!outputText.isEmpty()) {
                    return outputText;
                }
                StringBuilder text = new StringBuilder();
                for (JsonNode output : root.path("output")) {
                    for (JsonNode content : output.path("content")) {
                        if (content.path("type").asText().equals("output_text")) {
                            text.append(content.path("text").asText());
                        }
                    }
                }
                return text.toString();
            }
            if (provider.equals("google")) {
                StringBuilder text = new StringBuilder();
                for (JsonNode candidate : root.path("candidates")) {
                    for (JsonNode part : candidate.path("content").path("parts")) {
                        text.append(part.path("text").asText());
                    }
                }
                return text.toString();
            }

            StringBuilder text = new StringBuilder();
            for (JsonNode content : root.path("content")) {
                if (content.path("type").asText().equals("text")) {
                    text.append(content.path("text").asText());
                }
            }
            return text.toString();
        } catch (Exception exception) {
            return responseBody;
        }
    }

    private String providerError(String provider, int status) {
        if (status == 401 || status == 403) {
            return provider + " rejected the request (HTTP " + status + "). Check the API key and account access.";
        }
        if (status == 404) {
            return provider + " could not find the configured model or endpoint (HTTP 404).";
        }
        return provider + " could not complete the request (HTTP " + status + "). You can try again.";
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not prepare the model request.", exception);
        }
    }

    static class ModelCallException extends RuntimeException {
        ModelCallException(String message) {
            super(message);
        }
    }
}
