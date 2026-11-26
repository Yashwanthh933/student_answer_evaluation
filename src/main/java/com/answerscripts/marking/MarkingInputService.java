package com.answerscripts.marking;

import com.answerscripts.questionpaper.QuestionPaperOption;
import com.answerscripts.questionpaper.QuestionPaperUploadService;
import com.answerscripts.rubric.RubricService;
import com.answerscripts.transcription.TranscriptionService;
import com.answerscripts.transcription.TranscriptionService.RunView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MarkingInputService {

    private static final String LEGACY_EXAM = "Unassigned (before Module 5)";
    private final JdbcTemplate jdbcTemplate;
    private final TranscriptionService transcriptionService;
    private final QuestionPaperUploadService questionPaperUploadService;
    private final RubricService rubricService;

    public MarkingInputService(
            JdbcTemplate jdbcTemplate,
            TranscriptionService transcriptionService,
            QuestionPaperUploadService questionPaperUploadService,
            RubricService rubricService) {
        this.jdbcTemplate = jdbcTemplate;
        this.transcriptionService = transcriptionService;
        this.questionPaperUploadService = questionPaperUploadService;
        this.rubricService = rubricService;
    }

    public MarkingInputs getInputs(UUID submissionId) {
        List<SubmissionExam> found = jdbcTemplate.query("""
                SELECT e.id, e.name, e.subject_name, e.branch, e.year
                FROM answer_submissions s JOIN exams e ON e.id = s.exam_id
                WHERE s.id = ?
                """, (rs, row) -> new SubmissionExam(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("subject_name"), rs.getString("branch"), rs.getInt("year")), submissionId);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission exam not found.");
        SubmissionExam exam = found.get(0);
        boolean legacy = exam.name().equals(LEGACY_EXAM);
        QuestionPaperOption current = questionPaperUploadService.currentForExam(exam.id());
        List<QuestionPaperOption> legacyPapers = legacy
                ? questionPaperUploadService.listForExam(exam.id()) : List.of();
        RubricService.RubricView rubric = rubricService.getRubric(exam.id());
        return new MarkingInputs(submissionId, exam.id(), exam.name(), exam.subjectName(), exam.branch(), exam.year(),
                legacy, current, legacyPapers, rubric.saved() && !rubric.rubricText().isBlank(),
                transcriptionService.latestSuccessfulReading(submissionId));
    }

    public record MarkingInputs(UUID submissionId, UUID examId, String examName, String subjectName,
                                String branch, int year, boolean legacyPlaceholder,
                                QuestionPaperOption currentPaper, List<QuestionPaperOption> legacyPapers,
                                boolean rubricSaved, RunView latestReading) {}

    private record SubmissionExam(UUID id, String name, String subjectName, String branch, int year) {}
}
