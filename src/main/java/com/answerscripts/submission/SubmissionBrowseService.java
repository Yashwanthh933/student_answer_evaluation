package com.answerscripts.submission;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SubmissionBrowseService {

    private final JdbcTemplate jdbcTemplate;

    public SubmissionBrowseService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SubmissionListItem> list(
            String scannerUsername, UUID examId, String subject, String branch, Integer year) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.id AS submission_id, e.id AS exam_id, e.name AS exam_name,
                       e.subject_name, e.branch, e.year, scanner.display_name AS scanner_name,
                       COUNT(p.id) AS page_count, s.created_at
                FROM answer_submissions s
                JOIN exams e ON e.id = s.exam_id
                JOIN users scanner ON scanner.id = s.scanner_user_id
                LEFT JOIN answer_pages p ON p.submission_id = s.id
                WHERE 1 = 1
                """);
        List<Object> parameters = new ArrayList<>();
        addScope(sql, parameters, scannerUsername, "scanner.username");
        addFilter(sql, parameters, examId, "e.id = ?");
        addFilter(sql, parameters, subject, "e.subject_name = ?");
        addFilter(sql, parameters, branch, "e.branch = ?");
        addFilter(sql, parameters, year, "e.year = ?");
        sql.append(" GROUP BY s.id, e.id, scanner.id ORDER BY s.created_at DESC, s.id DESC");

        return jdbcTemplate.query(sql.toString(), (resultSet, rowNumber) -> new SubmissionListItem(
                resultSet.getObject("submission_id", UUID.class),
                resultSet.getObject("exam_id", UUID.class),
                resultSet.getString("exam_name"),
                resultSet.getString("subject_name"),
                resultSet.getString("branch"),
                resultSet.getInt("year"),
                resultSet.getString("scanner_name"),
                resultSet.getInt("page_count"),
                resultSet.getTimestamp("created_at").toInstant()), parameters.toArray());
    }

    public FilterOptions filterOptions(String scannerUsername) {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT e.id AS exam_id, e.name AS exam_name,
                       e.subject_name, e.branch, e.year
                FROM exams e
                LEFT JOIN answer_submissions s ON s.exam_id = e.id
                LEFT JOIN users scanner ON scanner.id = s.scanner_user_id
                WHERE 1 = 1
                """);
        List<Object> parameters = new ArrayList<>();
        if (scannerUsername != null) {
            sql.append(" AND scanner.username = ? AND s.id IS NOT NULL");
            parameters.add(scannerUsername);
        }
        sql.append(" ORDER BY e.name, e.subject_name, e.branch, e.year, e.id");

        List<ExamOption> exams = new ArrayList<>();
        List<String> subjects = new ArrayList<>();
        List<String> branches = new ArrayList<>();
        List<Integer> years = new ArrayList<>();
        jdbcTemplate.query(sql.toString(), resultSet -> {
            exams.add(new ExamOption(resultSet.getObject("exam_id", UUID.class),
                    resultSet.getString("exam_name"), resultSet.getString("subject_name"),
                    resultSet.getString("branch"), resultSet.getInt("year")));
            addDistinct(subjects, resultSet.getString("subject_name"));
            addDistinct(branches, resultSet.getString("branch"));
            addDistinct(years, resultSet.getInt("year"));
        }, parameters.toArray());
        return new FilterOptions(List.copyOf(exams), List.copyOf(subjects),
                List.copyOf(branches), List.copyOf(years));
    }

    public List<BrowseExamGroup> browse(String scannerUsername) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.branch, e.year, e.id AS exam_id, e.name AS exam_name,
                       e.subject_name, COUNT(s.id) AS submission_count
                FROM exams e
                LEFT JOIN answer_submissions s ON s.exam_id = e.id
                """);
        List<Object> parameters = new ArrayList<>();
        if (scannerUsername != null) {
            sql.append(" AND s.scanner_user_id = (SELECT id FROM users WHERE username = ?)");
            parameters.add(scannerUsername);
        }
        sql.append(" GROUP BY e.id, e.branch, e.year");
        if (scannerUsername != null) {
            sql.append(" HAVING COUNT(s.id) > 0");
        }
        sql.append(" ORDER BY e.branch, e.year, e.name, e.id");

        return jdbcTemplate.query(sql.toString(), (resultSet, rowNumber) -> new BrowseExamGroup(
                resultSet.getString("branch"), resultSet.getInt("year"),
                resultSet.getObject("exam_id", UUID.class), resultSet.getString("exam_name"),
                resultSet.getString("subject_name"), resultSet.getLong("submission_count")),
                parameters.toArray());
    }

    private static void addScope(
            StringBuilder sql, List<Object> parameters, String scannerUsername, String column) {
        if (scannerUsername != null) {
            sql.append(" AND ").append(column).append(" = ?");
            parameters.add(scannerUsername);
        }
    }

    private static void addFilter(StringBuilder sql, List<Object> parameters, Object value, String condition) {
        if (value != null && (!(value instanceof String string) || !string.isBlank())) {
            sql.append(" AND ").append(condition);
            parameters.add(value);
        }
    }

    private static <T> void addDistinct(List<T> values, T value) {
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    public record SubmissionListItem(
            UUID submissionId, UUID examId, String examName, String subjectName, String branch,
            int year, String scannerName, int pageCount, Instant submittedAt) {}

    public record ExamOption(UUID id, String name, String subjectName, String branch, int year) {}

    public record FilterOptions(
            List<ExamOption> exams, List<String> subjects, List<String> branches, List<Integer> years) {}

    public record BrowseExamGroup(
            String branch, int year, UUID examId, String examName, String subjectName, long submissionCount) {}
}
