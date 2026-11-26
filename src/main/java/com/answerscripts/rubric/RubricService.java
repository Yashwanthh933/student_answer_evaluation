package com.answerscripts.rubric;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RubricService {

    private static final Pattern QUESTION_LINE = Pattern.compile(
            "^Q(\\d+[A-Za-z]?)\\s+\\(([0-9]+(?:\\.[0-9]+)?)\\s+marks?\\)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUESTION_PREFIX = Pattern.compile("^Q(\\d+[A-Za-z]?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CRITERION_LINE = Pattern.compile(
            "^-\\s+(.+):\\s*([0-9]+(?:\\.[0-9]+)?)\\s*$");

    private final JdbcTemplate jdbcTemplate;

    public RubricService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ExamOption> listExams() {
        return jdbcTemplate.query("""
                SELECT id, name, subject_name, branch, year, status
                FROM exams ORDER BY exam_date DESC, name, branch, year
                """, (rs, row) -> new ExamOption(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("subject_name"), rs.getString("branch"), rs.getInt("year"), rs.getString("status")));
    }

    public RubricView getRubric(UUID examId) {
        ensureExamExists(examId);
        List<SavedRubric> saved = jdbcTemplate.query(
                "SELECT rubric_text, updated_at FROM exam_rubrics WHERE exam_id = ?",
                (resultSet, rowNumber) -> new SavedRubric(
                        resultSet.getString("rubric_text"),
                        resultSet.getTimestamp("updated_at").toInstant().toString()),
                examId);
        if (saved.isEmpty()) {
            return view(examId, "", null, false);
        }
        SavedRubric rubric = saved.get(0);
        return view(examId, rubric.text(), rubric.updatedAt(), true);
    }

    public List<String> questionNumbersIn(String rubricText) {
        return analyze(rubricText).questionNumbers();
    }

    public RubricView saveRubric(UUID examId, String username, String rubricText) {
        ensureExamExists(examId);
        if (rubricText == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rubric text is required.");
        }

        jdbcTemplate.update("""
                INSERT INTO exam_rubrics (exam_id, rubric_text, updated_by)
                VALUES (?, ?, (SELECT id FROM users WHERE username = ? AND enabled = TRUE))
                ON CONFLICT (exam_id) DO UPDATE
                SET rubric_text = EXCLUDED.rubric_text,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = CURRENT_TIMESTAMP
                """, examId, rubricText, username);
        return getRubric(examId);
    }

    private RubricView view(UUID examId, String rubricText, String updatedAt, boolean saved) {
        Analysis analysis = analyze(rubricText);
        return new RubricView(
                examId,
                rubricText,
                updatedAt,
                saved,
                analysis.questionNumbers(),
                analysis.warnings());
    }

    private void ensureExamExists(UUID examId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM exams WHERE id = ?)", Boolean.class, examId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam not found.");
        }
    }

    private Analysis analyze(String rubricText) {
        List<String> warnings = new ArrayList<>();
        List<String> questionNumbers = new ArrayList<>();
        Set<String> questionKeys = new HashSet<>();
        Set<String> seenQuestions = new HashSet<>();
        QuestionBlock current = null;
        String[] lines = rubricText.split("\\R", -1);

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            int lineNumber = index + 1;
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            Matcher questionMatcher = QUESTION_LINE.matcher(line);
            if (questionMatcher.matches()) {
                addTotalWarning(current, warnings);
                String questionNumber = "Q" + questionMatcher.group(1);
                String key = questionNumber.toUpperCase(Locale.ROOT);
                if (!seenQuestions.add(key)) {
                    warnings.add(questionNumber + ": question number appears twice.");
                }
                if (questionKeys.add(key)) {
                    questionNumbers.add(questionNumber);
                }
                current = new QuestionBlock(questionNumber, new BigDecimal(questionMatcher.group(2)));
                continue;
            }

            Matcher criterionMatcher = CRITERION_LINE.matcher(line);
            if (current != null && criterionMatcher.matches()) {
                current.criteriaTotal = current.criteriaTotal.add(new BigDecimal(criterionMatcher.group(2)));
                continue;
            }

            Matcher questionPrefix = QUESTION_PREFIX.matcher(line);
            if (questionPrefix.find()) {
                addTotalWarning(current, warnings);
                warnings.add("Q" + questionPrefix.group(1) + ": line " + lineNumber + " could not be read.");
                current = null;
                continue;
            }

            String questionNumber = current == null ? questionNumberFrom(line) : current.questionNumber;
            warnings.add(questionNumber + ": line " + lineNumber + " could not be read.");
        }

        addTotalWarning(current, warnings);
        return new Analysis(List.copyOf(questionNumbers), List.copyOf(warnings));
    }

    private void addTotalWarning(QuestionBlock question, List<String> warnings) {
        if (question != null && question.criteriaTotal.compareTo(question.maximumMarks) != 0) {
            warnings.add(question.questionNumber + ": criteria add up to "
                    + format(question.criteriaTotal) + " marks, but the question is worth "
                    + format(question.maximumMarks) + " marks.");
        }
    }

    private String questionNumberFrom(String line) {
        Matcher matcher = QUESTION_PREFIX.matcher(line);
        return matcher.find() ? "Q" + matcher.group(1) : "Qunknown";
    }

    private String format(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public record RubricView(
            UUID examId,
            String rubricText,
            String updatedAt,
            boolean saved,
            List<String> questionNumbers,
            List<String> warnings) {}

    public record ExamOption(UUID id, String name, String subjectName, String branch, int year, String status) {}

    private record SavedRubric(String text, String updatedAt) {}

    private record Analysis(List<String> questionNumbers, List<String> warnings) {}

    private static class QuestionBlock {
        private final String questionNumber;
        private final BigDecimal maximumMarks;
        private BigDecimal criteriaTotal = BigDecimal.ZERO;

        private QuestionBlock(String questionNumber, BigDecimal maximumMarks) {
            this.questionNumber = questionNumber;
            this.maximumMarks = maximumMarks;
        }
    }
}
