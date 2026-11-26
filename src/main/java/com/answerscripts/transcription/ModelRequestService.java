package com.answerscripts.transcription;

import java.util.List;

import com.answerscripts.admin.ModelDefinition;
import org.springframework.stereotype.Service;

@Service
public class ModelRequestService {

    private final ModelProviderClient modelProviderClient;

    public ModelRequestService(ModelProviderClient modelProviderClient) {
        this.modelProviderClient = modelProviderClient;
    }

    public String markAnswers(
            String provider,
            String modelName,
            String endpoint,
            String apiKey,
            String instructions,
            String studentAnswers,
            List<byte[]> questionPaperPages) throws InterruptedException {
        return markAnswers(
                provider,
                modelName,
                endpoint,
                apiKey,
                instructions,
                null,
                studentAnswers,
                questionPaperPages);
    }

    public String markAnswers(
            String provider,
            String modelName,
            String endpoint,
            String apiKey,
            String instructions,
            String rubricText,
            String studentAnswers,
            List<byte[]> questionPaperPages) throws InterruptedException {
        ModelDefinition model = new ModelDefinition(
                "marking", provider, modelName, endpoint, "", true);
        try {
            return modelProviderClient.markAnswers(
                    model, apiKey, instructions, rubricText, studentAnswers, questionPaperPages);
        } catch (ModelProviderClient.ModelCallException exception) {
            throw new ModelRequestException(exception.getMessage());
        }
    }

    public static class ModelRequestException extends RuntimeException {
        public ModelRequestException(String message) {
            super(message);
        }
    }
}
