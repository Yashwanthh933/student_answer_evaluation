package com.answerscripts.transcription;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/transcriptions")
public class TranscriptionController {

    private final TranscriptionService transcriptionService;

    public TranscriptionController(TranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    @GetMapping("/submissions")
    List<TranscriptionService.SubmissionItem> listSubmissions() {
        return transcriptionService.listSubmissions();
    }

    @GetMapping("/submissions/{submissionId}")
    TranscriptionService.SubmissionDetail submission(@PathVariable UUID submissionId) {
        return transcriptionService.getSubmission(submissionId);
    }

    @GetMapping("/submissions/{submissionId}/runs/{runId}")
    TranscriptionService.RunView run(
            @PathVariable UUID submissionId,
            @PathVariable UUID runId) {
        return transcriptionService.getRun(submissionId, runId);
    }

    @PostMapping("/submissions/{submissionId}/read")
    TranscriptionService.RunView readPages(@PathVariable UUID submissionId) {
        return transcriptionService.readPages(submissionId);
    }

    @GetMapping("/submissions/{submissionId}/pages/{pageNumber}/image")
    ResponseEntity<Resource> pageImage(
            @PathVariable UUID submissionId,
            @PathVariable int pageNumber) {
        try {
            TranscriptionService.PageImage image = transcriptionService.loadPageImage(submissionId, pageNumber);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(image.contentType()))
                    .body(image.resource());
        } catch (NoSuchFileException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stored page image not found.", exception);
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not read the stored page image.",
                    exception);
        }
    }
}
