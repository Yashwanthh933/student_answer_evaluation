package com.answerscripts.rubric;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rubrics")
public class RubricController {

    private final RubricService rubricService;

    public RubricController(RubricService rubricService) {
        this.rubricService = rubricService;
    }

    @GetMapping("/exams")
    List<RubricService.ExamOption> listExams() {
        return rubricService.listExams();
    }

    @GetMapping("/exams/{examId}")
    RubricService.RubricView getRubric(@PathVariable UUID examId) {
        return rubricService.getRubric(examId);
    }

    @PostMapping("/exams/{examId}")
    RubricService.RubricView saveRubric(
            Authentication authentication,
            @PathVariable UUID examId,
            @RequestBody SaveRubricRequest request) {
        return rubricService.saveRubric(examId, authentication.getName(), request.rubricText());
    }

    public record SaveRubricRequest(String rubricText) {}
}
