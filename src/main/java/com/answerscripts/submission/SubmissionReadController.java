package com.answerscripts.submission;

import com.answerscripts.storage.FileStorage;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SubmissionReadController {

    private final SubmissionReadService submissionReadService;
    private final FileStorage fileStorage;

    public SubmissionReadController(
            SubmissionReadService submissionReadService,
            FileStorage fileStorage) {
        this.submissionReadService = submissionReadService;
        this.fileStorage = fileStorage;
    }

    @GetMapping("/api/submissions")
    List<SubmissionReadService.SubmissionView> listSubmissions(Authentication authentication) {
        return submissionReadService.listForScanner(authentication.getName());
    }

    @GetMapping("/api/submissions/{submissionId}/pages/{pageNumber}/image")
    ResponseEntity<Resource> pageImage(
            @PathVariable UUID submissionId,
            @PathVariable int pageNumber,
            Authentication authentication) {
        SubmissionReadService.StoredPage page = submissionReadService.findPage(
                submissionId,
                pageNumber,
                authentication.getName());

        try {
            Resource image = fileStorage.load(page.storedFilename());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(page.contentType()))
                    .body(image);
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
