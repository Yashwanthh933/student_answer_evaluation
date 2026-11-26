package com.answerscripts.marking;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/marking/submissions")
public class MarkingInputController {

    private final MarkingInputService markingInputService;
    private final MarkingService markingService;

    public MarkingInputController(MarkingInputService markingInputService, MarkingService markingService) {
        this.markingInputService = markingInputService;
        this.markingService = markingService;
    }

    @GetMapping("/{submissionId}/inputs")
    MarkingInputService.MarkingInputs getInputs(@PathVariable UUID submissionId) {
        return markingInputService.getInputs(submissionId);
    }

    @GetMapping("/{submissionId}/latest")
    LatestMarkingResponse getLatestRun(@PathVariable UUID submissionId) {
        return new LatestMarkingResponse(markingService.latestRun(submissionId));
    }

    @GetMapping("/{submissionId}/runs")
    List<MarkingService.MarkingRunSummary> listRuns(@PathVariable UUID submissionId) {
        return markingService.listRuns(submissionId);
    }

    @GetMapping("/{submissionId}/runs/{runId}")
    MarkingService.MarkingRunView getRun(@PathVariable UUID submissionId, @PathVariable UUID runId) {
        return markingService.getRun(submissionId, runId);
    }

    @PostMapping("/{submissionId}/runs")
    MarkingService.MarkingRunView markAnswers(
            @PathVariable UUID submissionId,
            @RequestBody MarkAnswersRequest request) {
        return markingService.markAnswers(submissionId, request.questionPaperId());
    }

    @PostMapping("/{submissionId}/rubric-runs")
    MarkingService.MarkingRunView markWithRubric(
            @PathVariable UUID submissionId,
            @RequestBody MarkAnswersRequest request) {
        return markingService.markWithRubric(submissionId, request.questionPaperId());
    }

    public record MarkAnswersRequest(UUID questionPaperId) {}

    public record LatestMarkingResponse(MarkingService.MarkingRunView latestRun) {}
}
