package com.answerscripts.admin;

import java.io.IOException;
import java.util.List;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
class ModelCatalog {

    private final List<ModelDefinition> models;

    ModelCatalog(ObjectMapper objectMapper) {
        try (var input = new ClassPathResource("models.json").getInputStream()) {
            models = List.copyOf(objectMapper.readValue(input, new TypeReference<List<ModelDefinition>>() { }));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load models.json.", exception);
        }
    }

    List<ModelDefinition> models() {
        return models;
    }

    ModelDefinition find(String id) {
        return models.stream()
                .filter(model -> model.id().equals(id))
                .findFirst()
                .orElse(null);
    }
}

