package com.answerscripts.questionpaper;

import com.answerscripts.exam.ExamService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class QuestionPaperUploadController {

    private final QuestionPaperUploadService questionPaperUploadService;
    private final ExamService examService;

    public QuestionPaperUploadController(QuestionPaperUploadService questionPaperUploadService, ExamService examService) {
        this.questionPaperUploadService = questionPaperUploadService;
        this.examService = examService;
    }

    @GetMapping("/api/question-papers/exams")
    List<ExamService.ExamView> listExams() {
        return examService.listExams();
    }

    @GetMapping("/api/question-papers/exams/{examId}")
    PaperState getPapers(@PathVariable UUID examId) {
        if (!examService.exists(examId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam not found.");
        return new PaperState(questionPaperUploadService.currentForExam(examId), questionPaperUploadService.listForExam(examId));
    }

    @PostMapping(path = "/api/exams/{examId}/question-papers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<UploadResponse> upload(
            @PathVariable UUID examId,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        UUID paperId = questionPaperUploadService.upload(file, authentication.getName(), examId);
        return ResponseEntity.status(HttpStatus.CREATED).body(new UploadResponse(paperId));
    }

    public record PaperState(QuestionPaperOption current, List<QuestionPaperOption> history) {}
    public record UploadResponse(UUID paperId) {}
}
