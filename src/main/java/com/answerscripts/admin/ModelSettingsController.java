package com.answerscripts.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class ModelSettingsController {

    private final ModelSettingsService modelSettingsService;

    public ModelSettingsController(ModelSettingsService modelSettingsService) {
        this.modelSettingsService = modelSettingsService;
    }

    @GetMapping
    ModelSettingsService.SettingsView getSettings() {
        return modelSettingsService.getSettings();
    }

    @PostMapping
    ModelSettingsService.SettingsView selectModel(@RequestBody ModelSelectionRequest request) {
        return modelSettingsService.selectModel(request.modelId());
    }
}
