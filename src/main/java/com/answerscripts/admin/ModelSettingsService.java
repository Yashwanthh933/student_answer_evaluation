package com.answerscripts.admin;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class ModelSettingsService {

    private final JdbcTemplate jdbcTemplate;
    private final ModelCatalog modelCatalog;

    public ModelSettingsService(JdbcTemplate jdbcTemplate, ModelCatalog modelCatalog) {
        this.jdbcTemplate = jdbcTemplate;
        this.modelCatalog = modelCatalog;
    }

    public SettingsView getSettings() {
        String selectedModelId = jdbcTemplate.query(
                        "SELECT selected_model_id FROM model_settings WHERE id = 1",
                        resultSet -> resultSet.next() ? resultSet.getString("selected_model_id") : null);
        List<ModelOption> models = modelCatalog.models().stream()
                .map(model -> new ModelOption(
                        model.id(),
                        model.provider(),
                        model.modelName(),
                        model.endpoint(),
                        model.apiKeyEnv(),
                        model.canReadImages(),
                        hasApiKey(model.apiKeyEnv())))
                .toList();
        return new SettingsView(models, selectedModelId);
    }

    public SettingsView selectModel(String modelId) {
        ModelDefinition model = modelCatalog.find(modelId);
        if (model == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Choose a model from the available list.");
        }
        if (!hasApiKey(model.apiKeyEnv())) {
            throw new ResponseStatusException(BAD_REQUEST, "This model is unavailable because its API key is missing.");
        }

        jdbcTemplate.update("""
                INSERT INTO model_settings (id, selected_model_id)
                VALUES (1, ?)
                ON CONFLICT (id) DO UPDATE
                SET selected_model_id = EXCLUDED.selected_model_id,
                    updated_at = CURRENT_TIMESTAMP
                """, modelId);
        return getSettings();
    }

    private boolean hasApiKey(String environmentVariable) {
        String key = System.getenv(environmentVariable);
        return key != null && !key.isBlank();
    }

    public record ModelOption(
            String id,
            String provider,
            String modelName,
            String endpoint,
            String apiKeyEnv,
            boolean canReadImages,
            boolean apiKeyPresent) {
    }

    public record SettingsView(List<ModelOption> models, String selectedModelId) {
    }
}
