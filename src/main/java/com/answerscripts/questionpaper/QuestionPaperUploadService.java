package com.answerscripts.questionpaper;

import com.answerscripts.storage.FileStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QuestionPaperUploadService {

    private static final byte[] PDF_HEADER = { '%', 'P', 'D', 'F', '-' };
    private static final long MAX_FILE_SIZE = 15L * 1024 * 1024;

    private final FileStorage fileStorage;
    private final JdbcTemplate jdbcTemplate;

    public QuestionPaperUploadService(FileStorage fileStorage, JdbcTemplate jdbcTemplate) {
        this.fileStorage = fileStorage;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public UUID upload(MultipartFile paper, String uploaderUsername, UUID examId) {
        if (paper == null || paper.isEmpty()) throw badRequest("Choose a PDF before uploading.");
        if (paper.getSize() > MAX_FILE_SIZE) throw badRequest("The PDF must be 15 MB or smaller.");
        if (!Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM exams WHERE id = ?)", Boolean.class, examId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam not found.");
        }

        UUID paperId = UUID.randomUUID();
        String displayFilename = displayFilename(paper.getOriginalFilename());
        String storedFilename = null;
        try (PushbackInputStream input = new PushbackInputStream(paper.getInputStream(), PDF_HEADER.length)) {
            byte[] signature = input.readNBytes(PDF_HEADER.length);
            if (!Arrays.equals(signature, PDF_HEADER)) throw badRequest("Upload a valid PDF file.");
            input.unread(signature);

            storedFilename = fileStorage.store(input, ".pdf");
            jdbcTemplate.update("UPDATE question_papers SET replaced = TRUE WHERE exam_id = ? AND replaced = FALSE", examId);
            jdbcTemplate.update("""
                    INSERT INTO question_papers
                        (id, exam_id, uploader_username, stored_filename, display_filename, content_type)
                    VALUES (?, ?, ?, ?, ?, 'application/pdf')
                    """, paperId, examId, uploaderUsername, storedFilename, displayFilename);
        } catch (IOException exception) {
            deleteStoredFile(storedFilename, exception);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store the PDF.", exception);
        } catch (RuntimeException exception) {
            deleteStoredFile(storedFilename, exception);
            throw exception;
        }
        return paperId;
    }

    public List<QuestionPaperOption> listForExam(UUID examId) {
        return jdbcTemplate.query("""
                SELECT id, exam_id, display_filename, uploaded_at, replaced
                FROM question_papers WHERE exam_id = ?
                ORDER BY replaced, uploaded_at DESC, id DESC
                """, (rs, row) -> new QuestionPaperOption(
                rs.getObject("id", UUID.class), rs.getObject("exam_id", UUID.class),
                rs.getString("display_filename"), rs.getTimestamp("uploaded_at").toInstant().toString(),
                rs.getBoolean("replaced")), examId);
    }

    public QuestionPaperOption currentForExam(UUID examId) {
        List<QuestionPaperOption> papers = jdbcTemplate.query("""
                SELECT id, exam_id, display_filename, uploaded_at, replaced
                FROM question_papers WHERE exam_id = ? AND replaced = FALSE
                ORDER BY uploaded_at DESC, id DESC LIMIT 1
                """, (rs, row) -> new QuestionPaperOption(
                rs.getObject("id", UUID.class), rs.getObject("exam_id", UUID.class),
                rs.getString("display_filename"), rs.getTimestamp("uploaded_at").toInstant().toString(),
                rs.getBoolean("replaced")), examId);
        return papers.isEmpty() ? null : papers.get(0);
    }

    public boolean belongsToExam(UUID examId, UUID paperId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM question_papers WHERE exam_id = ? AND id = ?)",
                Boolean.class, examId, paperId));
    }

    public byte[] loadPdf(UUID paperId) throws IOException {
        List<String> filenames = jdbcTemplate.query(
                "SELECT stored_filename FROM question_papers WHERE id = ?",
                (rs, row) -> rs.getString("stored_filename"), paperId);
        if (filenames.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Question paper not found.");
        try (InputStream input = fileStorage.load(filenames.get(0)).getInputStream()) {
            return input.readAllBytes();
        }
    }

    private String displayFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) return "Question paper.pdf";
        String basename = originalFilename.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").trim();
        if (basename.isEmpty()) return "Question paper.pdf";
        return basename.length() > 255 ? basename.substring(0, 255) : basename;
    }

    private void deleteStoredFile(String filename, Exception originalException) {
        if (filename == null) return;
        try {
            fileStorage.delete(filename);
        } catch (IOException cleanupException) {
            originalException.addSuppressed(cleanupException);
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
