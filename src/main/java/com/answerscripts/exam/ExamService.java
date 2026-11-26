package com.answerscripts.exam;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExamService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Path referenceDataFile;

    public ExamService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Value("${app.exam.reference-data-file:./config/reference-data.json}") String referenceDataFile) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.referenceDataFile = Path.of(referenceDataFile);
    }

    public ReferenceData referenceData() {
        try (var input = Files.newInputStream(referenceDataFile)) {
            ReferenceData data = objectMapper.readValue(input, ReferenceData.class);
            if (data.branches() == null || data.branches().isEmpty() || data.years() == null || data.years().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Exam reference data must include branches and years.");
            }
            return data;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read exam reference data.");
        }
    }

    public List<ExamView> listExams() {
        return jdbcTemplate.query("""
                SELECT e.id, e.name, e.subject_name, e.course_code, e.branch, e.year, e.exam_date,
                       e.status, creator.username AS created_by_username, e.created_at
                FROM exams e JOIN users creator ON creator.id = e.created_by
                ORDER BY e.exam_date DESC, e.name, e.branch, e.year, e.created_at DESC
                """, (rs, row) -> new ExamView(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("subject_name"),
                rs.getString("course_code"), rs.getString("branch"), rs.getInt("year"),
                rs.getDate("exam_date").toLocalDate().toString(), rs.getString("status"),
                rs.getString("created_by_username"), rs.getTimestamp("created_at").toInstant().toString()));
    }

    public List<OpenExamView> listOpenExams() {
        return jdbcTemplate.query("""
                SELECT id, name, subject_name, branch, year
                FROM exams WHERE status = 'OPEN'
                ORDER BY exam_date DESC, name, subject_name, branch, year
                """, (rs, row) -> new OpenExamView(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("subject_name"), rs.getString("branch"), rs.getInt("year")));
    }

    @Transactional
    public void requireOpenExam(UUID examId) {
        List<UUID> open = jdbcTemplate.query(
                "SELECT id FROM exams WHERE id = ? AND status = 'OPEN' FOR SHARE",
                (rs, row) -> rs.getObject("id", UUID.class), examId);
        if (open.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This exam is closed or no longer available.");
        }
    }

    public UUID enabledUserId(String username) {
        return userId(username);
    }

    public boolean exists(UUID examId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM exams WHERE id = ?)", Boolean.class, examId));
    }

    public String examName(UUID examId) {
        List<String> names = jdbcTemplate.query(
                "SELECT name FROM exams WHERE id = ?", (rs, row) -> rs.getString("name"), examId);
        if (names.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Exam not found.");
        return names.get(0);
    }

    @Transactional
    public ExamView create(String username, ExamRequest request) {
        ExamFields fields = validate(request);
        UUID creatorId = userId(username);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO exams (id, name, subject_name, course_code, branch, year, exam_date, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, fields.name(), fields.subjectName(), fields.courseCode(), fields.branch(), fields.year(),
                Date.valueOf(fields.examDate()), fields.status(), creatorId);
        return find(id);
    }

    @Transactional
    public ExamView update(UUID id, ExamRequest request) {
        ExamFields fields = validate(request);
        int changed = jdbcTemplate.update("""
                UPDATE exams SET name = ?, subject_name = ?, course_code = ?, branch = ?, year = ?, exam_date = ?, status = ?
                WHERE id = ?
                """, fields.name(), fields.subjectName(), fields.courseCode(), fields.branch(), fields.year(),
                Date.valueOf(fields.examDate()), fields.status(), id);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam not found.");
        return find(id);
    }

    private ExamView find(UUID id) {
        List<ExamView> found = jdbcTemplate.query("""
                SELECT e.id, e.name, e.subject_name, e.course_code, e.branch, e.year, e.exam_date,
                       e.status, creator.username AS created_by_username, e.created_at
                FROM exams e JOIN users creator ON creator.id = e.created_by WHERE e.id = ?
                """, (rs, row) -> new ExamView(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("subject_name"),
                rs.getString("course_code"), rs.getString("branch"), rs.getInt("year"),
                rs.getDate("exam_date").toLocalDate().toString(), rs.getString("status"),
                rs.getString("created_by_username"), rs.getTimestamp("created_at").toInstant().toString()), id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam not found.");
        return found.get(0);
    }

    private ExamFields validate(ExamRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Exam details are required.");
        String name = required(request.name(), "Exam name", 200);
        String subject = required(request.subjectName(), "Subject name", 200);
        String code = optional(request.courseCode(), 80, "Course code");
        String branch = required(request.branch(), "Branch", 80);
        if (!referenceData().branches().contains(branch)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a branch from the reference list.");
        }
        if (request.year() == null || !referenceData().years().contains(request.year())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a year from the reference list.");
        }
        String date = required(request.examDate(), "Exam date", 10);
        try {
            java.time.LocalDate.parse(date);
        } catch (java.time.DateTimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid exam date.");
        }
        String status = request.status() == null ? "OPEN" : request.status().trim().toUpperCase(java.util.Locale.ROOT);
        if (!status.equals("OPEN") && !status.equals("CLOSED")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status must be OPEN or CLOSED.");
        }
        return new ExamFields(name, subject, code, branch, request.year(), date, status);
    }

    private UUID userId(String username) {
        List<UUID> ids = jdbcTemplate.query(
                "SELECT id FROM users WHERE username = ? AND enabled = TRUE",
                (rs, row) -> rs.getObject("id", UUID.class), username);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The signed-in account is not enabled.");
        return ids.get(0);
    }

    private String required(String value, String label, int max) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " is required and must be at most " + max + " characters.");
        }
        return clean;
    }

    private String optional(String value, int max, String label) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " must be at most " + max + " characters.");
        return clean.isEmpty() ? null : clean;
    }

    public record ReferenceData(List<String> branches, List<Integer> years) {}
    public record OpenExamView(UUID id, String name, String subjectName, String branch, int year) {}
    public record ExamRequest(String name, String subjectName, String courseCode, String branch, Integer year, String examDate, String status) {}
    public record ExamView(UUID id, String name, String subjectName, String courseCode, String branch, int year,
                           String examDate, String status, String createdByUsername, String createdAt) {}
    private record ExamFields(String name, String subjectName, String courseCode, String branch, int year,
                              String examDate, String status) {}
}
