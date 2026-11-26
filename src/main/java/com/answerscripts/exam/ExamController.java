package com.answerscripts.exam;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exams")
public class ExamController {

    private final ExamService examService;

    public ExamController(ExamService examService) {
        this.examService = examService;
    }

    @GetMapping("/reference-data")
    ExamService.ReferenceData referenceData() {
        return examService.referenceData();
    }

    @GetMapping
    List<ExamService.ExamView> list() {
        return examService.listExams();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ExamService.ExamView create(Authentication authentication, @RequestBody ExamService.ExamRequest request) {
        return examService.create(authentication.getName(), request);
    }

    @PutMapping("/{id}")
    ExamService.ExamView update(@PathVariable UUID id, @RequestBody ExamService.ExamRequest request) {
        return examService.update(id, request);
    }
}
