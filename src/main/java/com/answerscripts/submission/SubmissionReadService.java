package com.answerscripts.submission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SubmissionReadService {

    private final JdbcTemplate jdbcTemplate;

    public SubmissionReadService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SubmissionView> listForScanner(String scannerUsername) {
        Map<UUID, List<PageView>> pagesBySubmission = new LinkedHashMap<>();
        Map<UUID, String> labelsBySubmission = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT s.id AS submission_id, e.name, e.subject_name, e.branch, e.year, p.page_number "
                        + "FROM answer_submissions s "
                        + "JOIN answer_pages p ON p.submission_id = s.id "
                        + "JOIN exams e ON e.id = s.exam_id "
                        + "JOIN users scanner ON scanner.id = s.scanner_user_id "
                        + "WHERE scanner.username = ? "
                        + "ORDER BY s.created_at DESC, s.id DESC, p.page_number ASC",
                resultSet -> {
                    UUID submissionId = resultSet.getObject("submission_id", UUID.class);
                    labelsBySubmission.putIfAbsent(submissionId,
                            resultSet.getString("name") + " · " + resultSet.getString("subject_name") + " · "
                                    + resultSet.getString("branch") + " year " + resultSet.getInt("year"));
                    int pageNumber = resultSet.getInt("page_number");
                    String imageUrl = "/api/submissions/" + submissionId
                            + "/pages/" + pageNumber + "/image";
                    pagesBySubmission.computeIfAbsent(submissionId, ignored -> new ArrayList<>())
                            .add(new PageView(pageNumber, imageUrl));
                },
                scannerUsername);

        return pagesBySubmission.entrySet().stream()
                .map(entry -> new SubmissionView(
                        entry.getKey(), labelsBySubmission.get(entry.getKey()), List.copyOf(entry.getValue())))
                .toList();
    }

    public List<AdminSubmissionView> listAll() {
        return jdbcTemplate.query(
                "SELECT s.id AS submission_id, COUNT(p.id) AS page_count "
                        + "FROM answer_submissions s LEFT JOIN answer_pages p ON p.submission_id = s.id "
                        + "GROUP BY s.id, s.created_at "
                        + "ORDER BY s.created_at DESC, s.id DESC",
                (resultSet, rowNumber) -> new AdminSubmissionView(
                        resultSet.getObject("submission_id", UUID.class),
                        resultSet.getInt("page_count")));
    }

    public List<AdminPage> pagesForAdmin(UUID submissionId) {
        boolean exists = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM answer_submissions WHERE id = ?)",
                Boolean.class,
                submissionId));
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found.");
        }

        return jdbcTemplate.query(
                "SELECT page_number, stored_filename, content_type FROM answer_pages "
                        + "WHERE submission_id = ? ORDER BY page_number ASC",
                (resultSet, rowNumber) -> new AdminPage(
                        resultSet.getInt("page_number"),
                        resultSet.getString("stored_filename"),
                        resultSet.getString("content_type")),
                submissionId);
    }

    public StoredPage findPageForAdmin(UUID submissionId, int pageNumber) {
        List<StoredPage> pages = jdbcTemplate.query(
                "SELECT p.stored_filename, p.content_type FROM answer_pages p "
                        + "WHERE p.submission_id = ? AND p.page_number = ?",
                (resultSet, rowNumber) -> new StoredPage(
                        resultSet.getString("stored_filename"),
                        resultSet.getString("content_type")),
                submissionId,
                pageNumber);
        if (pages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission page not found.");
        }
        return pages.get(0);
    }

    public StoredPage findPage(UUID submissionId, int pageNumber, String scannerUsername) {
        List<StoredPage> pages = jdbcTemplate.query(
                "SELECT p.stored_filename, p.content_type FROM answer_pages p "
                        + "JOIN answer_submissions s ON s.id = p.submission_id "
                        + "JOIN users scanner ON scanner.id = s.scanner_user_id "
                        + "WHERE s.id = ? AND p.page_number = ? AND scanner.username = ?",
                (resultSet, rowNumber) -> new StoredPage(
                        resultSet.getString("stored_filename"),
                        resultSet.getString("content_type")),
                submissionId,
                pageNumber,
                scannerUsername);

        if (pages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission page not found.");
        }
        return pages.get(0);
    }

    public record SubmissionView(UUID submissionId, String examLabel, List<PageView> pages) {}

    public record PageView(int pageNumber, String imageUrl) {}

    public record StoredPage(String storedFilename, String contentType) {}

    public record AdminSubmissionView(UUID submissionId, int pageCount) {}

    public record AdminPage(int pageNumber, String storedFilename, String contentType) {}
}
