package com.answerscripts.submission;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/submission-browser")
public class SubmissionBrowseController {

    private final SubmissionBrowseService browseService;

    public SubmissionBrowseController(SubmissionBrowseService browseService) {
        this.browseService = browseService;
    }

    @GetMapping("/submissions")
    SubmissionListResponse submissions(
            @RequestParam(required = false) UUID examId,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) Integer year,
            Authentication authentication) {
        String scannerUsername = scannerUsername(authentication);
        return new SubmissionListResponse(scannerUsername != null,
                browseService.list(scannerUsername, examId, subject, branch, year));
    }

    @GetMapping("/filters")
    SubmissionBrowseService.FilterOptions filters(Authentication authentication) {
        return browseService.filterOptions(scannerUsername(authentication));
    }

    @GetMapping("/browse")
    BrowseResponse browse(Authentication authentication) {
        String scannerUsername = scannerUsername(authentication);
        List<SubmissionBrowseService.BrowseExamGroup> groups = browseService.browse(scannerUsername);
        return new BrowseResponse(scannerUsername != null, groups);
    }

    private static String scannerUsername(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_SCANNER"::equals) ? authentication.getName() : null;
    }

    public record SubmissionListResponse(
            boolean scanner, List<SubmissionBrowseService.SubmissionListItem> submissions) {}

    public record BrowseResponse(
            boolean scanner, List<SubmissionBrowseService.BrowseExamGroup> groups) {}
}
