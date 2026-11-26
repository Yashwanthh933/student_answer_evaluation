package com.answerscripts.submission;

import java.util.UUID;
import java.util.List;

import com.answerscripts.exam.ExamService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class SubmissionUploadController {

    private final ImageUploadService imageUploadService;
    private final ExamService examService;

    public SubmissionUploadController(ImageUploadService imageUploadService, ExamService examService) {
        this.imageUploadService = imageUploadService;
        this.examService = examService;
    }

    @GetMapping("/csrf")
    CsrfResponse csrfToken(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }

    @GetMapping("/api/submissions/open-exams")
    List<ExamService.OpenExamView> openExams() {
        return examService.listOpenExams();
    }

    @PostMapping(path = "/api/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<UploadResponse> upload(
            @RequestPart("manifest") PageManifest manifest,
            MultipartHttpServletRequest request,
            Authentication authentication) {
        MultiValueMap<String, MultipartFile> imageParts = new LinkedMultiValueMap<>(request.getMultiFileMap());
        imageParts.remove("manifest");
        UUID submissionId = imageUploadService.upload(
                manifest.examId(), manifest.pages(), imageParts, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new UploadResponse(submissionId, manifest.pages().size()));
    }

    public record CsrfResponse(String headerName, String token) {}

    public record UploadResponse(UUID submissionId, int pageCount) {}
}
