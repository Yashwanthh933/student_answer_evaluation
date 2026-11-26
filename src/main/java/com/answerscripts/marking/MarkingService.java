package com.answerscripts.marking;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.answerscripts.admin.ModelSettingsService;
import com.answerscripts.questionpaper.QuestionPaperUploadService;
import com.answerscripts.rubric.RubricService;
import com.answerscripts.transcription.ModelRequestService;
import com.answerscripts.transcription.ModelRequestService.ModelRequestException;
import com.answerscripts.transcription.TranscriptionService;
import com.answerscripts.transcription.TranscriptionService.Answer;
import com.answerscripts.transcription.TranscriptionService.RunView;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MarkingService {

    private static final int MAX_IMAGE_SIDE = 1600;
    private static final float PDF_RENDER_DPI = 150;

    private final JdbcTemplate jdbcTemplate;
    private final TranscriptionService transcriptionService;
    private final QuestionPaperUploadService questionPaperUploadService;
    private final ModelSettingsService modelSettingsService;
    private final ModelRequestService modelRequestService;
    private final RubricService rubricService;
    private final ObjectMapper objectMapper;
    private final Path instructionsFile;
    private final Path rubricInstructionsFile;

    public MarkingService(
            JdbcTemplate jdbcTemplate,
            TranscriptionService transcriptionService,
            QuestionPaperUploadService questionPaperUploadService,
            ModelSettingsService modelSettingsService,
            ModelRequestService modelRequestService,
            RubricService rubricService,
            ObjectMapper objectMapper,
            @Value("${app.marking.instructions-file}") String instructionsFile,
            @Value("${app.rubric-marking.instructions-file}") String rubricInstructionsFile) {
        this.jdbcTemplate = jdbcTemplate;
        this.transcriptionService = transcriptionService;
        this.questionPaperUploadService = questionPaperUploadService;
        this.modelSettingsService = modelSettingsService;
        this.modelRequestService = modelRequestService;
        this.rubricService = rubricService;
        this.objectMapper = objectMapper;
        this.instructionsFile = Path.of(instructionsFile);
        this.rubricInstructionsFile = Path.of(rubricInstructionsFile);
    }

    public MarkingRunView markAnswers(UUID submissionId, UUID questionPaperId) {
        PaperSelection selection = paperForSubmission(submissionId, questionPaperId);
        return runMarking(submissionId, selection.paperId(), "no-rubric", null);
    }

    public MarkingRunView markWithRubric(UUID submissionId, UUID questionPaperId) {
        PaperSelection selection = paperForSubmission(submissionId, questionPaperId);
        RubricService.RubricView rubric = rubricService.getRubric(selection.examId());
        if (!rubric.saved() || rubric.rubricText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Save a rubric for this exam first.");
        }
        return runMarking(submissionId, selection.paperId(), "rubric", rubric.rubricText());
    }

    private PaperSelection paperForSubmission(UUID submissionId, UUID requestedPaperId) {
        List<SubmissionExam> exams = jdbcTemplate.query("""
                SELECT e.id, e.name FROM answer_submissions s JOIN exams e ON e.id = s.exam_id WHERE s.id = ?
                """, (rs, row) -> new SubmissionExam(rs.getObject("id", UUID.class), rs.getString("name")), submissionId);
        if (exams.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission exam not found.");
        SubmissionExam exam = exams.get(0);
        UUID paperId;
        if (exam.name().equals("Unassigned (before Module 5)")) {
            if (requestedPaperId == null || !questionPaperUploadService.belongsToExam(exam.id(), requestedPaperId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a question paper for this older submission.");
            }
            paperId = requestedPaperId;
        } else {
            var current = questionPaperUploadService.currentForExam(exam.id());
            if (current == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a question paper for this exam first.");
            paperId = current.id();
        }
        return new PaperSelection(exam.id(), paperId);
    }

    private MarkingRunView runMarking(
            UUID submissionId, UUID questionPaperId, String runType, String rubricText) {
        RunView reading = transcriptionService.latestSuccessfulReading(submissionId);
        if (reading == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Read the pages first.");
        }

        ModelSettingsService.SettingsView settings = modelSettingsService.getSettings();
        ModelSettingsService.ModelOption selected = settings.models().stream()
                .filter(model -> model.id().equals(settings.selectedModelId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Choose a model in Model settings before marking answers."));
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO marking_runs (id, submission_id, reading_run_id, question_paper_id, provider, model_name, "
                        + "run_type, rubric_text) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                runId,
                submissionId,
                reading.id(),
                questionPaperId,
                selected.provider(),
                selected.modelName(),
                runType,
                rubricText);

        String rawReply = null;
        try {
            if (!selected.canReadImages()) {
                throw new ModelRequestException("The selected model cannot read images. Choose an image-capable model in Model settings.");
            }

            String apiKey = System.getenv(selected.apiKeyEnv());
            if (apiKey == null || apiKey.isBlank()) {
                throw new ModelRequestException(
                        "The API key variable " + selected.apiKeyEnv() + " is not set. Add it and try again.");
            }

            Path selectedInstructions = runType.equals("rubric") ? rubricInstructionsFile : instructionsFile;
            String instructions = Files.readString(selectedInstructions);
            byte[] questionPaper = questionPaperUploadService.loadPdf(questionPaperId);
            List<byte[]> pageImages = renderQuestionPaper(questionPaper);
            String answersText = formatAnswers(reading.answers());
            rawReply = modelRequestService.markAnswers(
                    selected.provider(),
                    selected.modelName(),
                    selected.endpoint(),
                    apiKey,
                    instructions,
                    rubricText,
                    answersText,
                    pageImages);

            List<MarkRow> marks;
            List<String> unassigned;
            try {
                ParsedMarks parsed = parseMarks(rawReply);
                marks = runType.equals("rubric")
                        ? addNoRubricPrefixes(rubricText, parsed.marks())
                        : parsed.marks();
                unassigned = parsed.unassigned();
            } catch (InvalidModelReplyException exception) {
                failRun(runId, rawReply, exception.getMessage());
                return findRun(submissionId, runId);
            }

            jdbcTemplate.update(
                    "UPDATE marking_runs SET succeeded = TRUE, raw_reply = ?, parsed_marks = ?::jsonb, "
                            + "unassigned = ?::jsonb, error_message = NULL WHERE id = ?",
                    rawReply,
                    marksJson(marks),
                    json(unassigned),
                    runId);
        } catch (ModelRequestException exception) {
            failRun(runId, rawReply, exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failRun(runId, rawReply, "The model request was interrupted. You can try again.");
        } catch (IOException exception) {
            failRun(runId, rawReply, "Could not prepare the question paper or read the marking instructions file.");
        } catch (ResponseStatusException exception) {
            failRun(runId, rawReply, "Could not open the selected question paper. Choose it again and try again.");
        } catch (Exception exception) {
            failRun(runId, rawReply, "The marking request could not be completed. You can try again.");
        }
        return findRun(submissionId, runId);
    }

    public MarkingRunView latestRun(UUID submissionId) {
        List<UUID> runIds = jdbcTemplate.query(
                "SELECT id FROM marking_runs WHERE submission_id = ? "
                        + "ORDER BY started_at DESC, id DESC LIMIT 1",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                submissionId);
        return runIds.isEmpty() ? null : findRun(submissionId, runIds.get(0));
    }

    public List<MarkingRunSummary> listRuns(UUID submissionId) {
        return jdbcTemplate.query(
                "SELECT id, run_type, provider, model_name, started_at, succeeded FROM marking_runs "
                        + "WHERE submission_id = ? ORDER BY started_at DESC, id DESC",
                (resultSet, rowNumber) -> new MarkingRunSummary(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("run_type"),
                        resultSet.getString("provider"),
                        resultSet.getString("model_name"),
                        resultSet.getTimestamp("started_at").toInstant().toString(),
                        resultSet.getBoolean("succeeded")),
                submissionId);
    }

    public MarkingRunView getRun(UUID submissionId, UUID runId) {
        return findRun(submissionId, runId);
    }

    private List<byte[]> renderQuestionPaper(byte[] pdfBytes) throws IOException {
        List<byte[]> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            for (int pageNumber = 0; pageNumber < document.getNumberOfPages(); pageNumber++) {
                BufferedImage rendered = renderer.renderImageWithDPI(pageNumber, PDF_RENDER_DPI);
                BufferedImage resized = shrinkToLimit(rendered);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!ImageIO.write(resized, "jpeg", output)) {
                    throw new IOException("JPEG encoder is unavailable.");
                }
                pages.add(output.toByteArray());
            }
        }
        if (pages.isEmpty()) {
            throw new IOException("The question paper has no pages.");
        }
        return List.copyOf(pages);
    }

    private record SubmissionExam(UUID id, String name) {}
    private record PaperSelection(UUID examId, UUID paperId) {}

    private BufferedImage shrinkToLimit(BufferedImage source) {
        double scale = Math.min(1.0, (double) MAX_IMAGE_SIDE / Math.max(source.getWidth(), source.getHeight()));
        if (scale == 1.0) {
            return source;
        }

        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

    private String formatAnswers(List<Answer> answers) {
        StringBuilder text = new StringBuilder();
        for (Answer answer : answers) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append("Question number: ").append(answer.q()).append('\n').append(answer.text());
        }
        return text.toString();
    }

    private ParsedMarks parseMarks(String rawReply) {
        try {
            JsonNode root = objectMapper.readTree(rawReply);
            JsonNode marksNode = root.get("marks");
            JsonNode unassignedNode = root.get("unassigned");
            if (marksNode == null || !marksNode.isArray()
                    || unassignedNode == null || !unassignedNode.isArray()) {
                throw new InvalidModelReplyException(
                        "The model reply was not valid JSON with marks and unassigned lists.");
            }

            List<MarkRow> marks = new ArrayList<>();
            for (JsonNode mark : marksNode) {
                JsonNode question = mark.get("q");
                JsonNode maximum = mark.get("max_marks");
                JsonNode awarded = mark.get("marks_awarded");
                JsonNode explanation = mark.get("explanation");
                if (question == null || !question.isTextual()
                        || maximum == null || !maximum.isNumber()
                        || awarded == null || !awarded.isNumber()
                        || explanation == null || !explanation.isTextual()) {
                    throw new InvalidModelReplyException(
                            "The model reply did not match the required marks format. You can try again.");
                }
                BigDecimal maxMarks = new BigDecimal(maximum.asText());
                BigDecimal marksAwarded = new BigDecimal(awarded.asText());
                if (maxMarks.signum() < 0 || marksAwarded.signum() < 0) {
                    throw new InvalidModelReplyException(
                            "The model reply contained an invalid mark value. You can try again.");
                }
                marks.add(new MarkRow(question.asText(), maxMarks, marksAwarded, explanation.asText()));
            }

            List<String> unassigned = new ArrayList<>();
            for (JsonNode answer : unassignedNode) {
                if (!answer.isTextual()) {
                    throw new InvalidModelReplyException(
                            "The model reply did not match the required unassigned answers format. You can try again.");
                }
                unassigned.add(answer.asText());
            }
            return new ParsedMarks(List.copyOf(marks), List.copyOf(unassigned));
        } catch (InvalidModelReplyException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidModelReplyException("The model reply was not valid JSON. You can try again.");
        }
    }

    private String marksJson(List<MarkRow> marks) {
        List<Map<String, Object>> values = marks.stream()
                .map(mark -> Map.<String, Object>of(
                        "q", mark.q(),
                        "max_marks", mark.maxMarks(),
                        "marks_awarded", mark.marksAwarded(),
                        "explanation", mark.explanation()))
                .toList();
        return json(values);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not save the marking result.", exception);
        }
    }

    private void failRun(UUID runId, String rawReply, String errorMessage) {
        jdbcTemplate.update(
                "UPDATE marking_runs SET succeeded = FALSE, raw_reply = ?, parsed_marks = NULL, "
                        + "unassigned = NULL, error_message = ? WHERE id = ?",
                rawReply,
                errorMessage,
                runId);
    }

    private MarkingRunView findRun(UUID submissionId, UUID runId) {
        List<MarkingRunView> runs = jdbcTemplate.query(
                "SELECT id, provider, model_name, started_at, succeeded, run_type, rubric_text, "
                        + "parsed_marks::text AS parsed_marks_json, unassigned::text AS unassigned_json, error_message "
                        + "FROM marking_runs "
                        + "WHERE submission_id = ? AND id = ?",
                (resultSet, rowNumber) -> {
                    List<MarkRow> marks = readMarks(resultSet.getString("parsed_marks_json"));
                    List<String> unassigned = readUnassigned(resultSet.getString("unassigned_json"));
                    String runType = resultSet.getString("run_type");
                    String rubricText = resultSet.getString("rubric_text");
                    return new MarkingRunView(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("provider"),
                            resultSet.getString("model_name"),
                            resultSet.getTimestamp("started_at").toInstant().toString(),
                            resultSet.getBoolean("succeeded"),
                            marks,
                            unassigned,
                            runType,
                            rubricText,
                            questionsWithoutRubric(runType, rubricText, marks),
                            marks.stream().map(MarkRow::marksAwarded)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add),
                            resultSet.getString("error_message"));
                },
                submissionId,
                runId);
        if (runs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Saved marking run not found.");
        }
        return runs.get(0);
    }

    private List<String> questionsWithoutRubric(String runType, String rubricText, List<MarkRow> marks) {
        if (!"rubric".equals(runType) || rubricText == null) {
            return List.of();
        }
        Set<String> covered = coveredQuestionNumbers(rubricText);
        Set<String> unscoredByRubric = new LinkedHashSet<>();
        for (MarkRow mark : marks) {
            if (!covered.contains(normalizeQuestionNumber(mark.q()))) {
                unscoredByRubric.add(mark.q());
            }
        }
        return List.copyOf(unscoredByRubric);
    }

    private List<MarkRow> addNoRubricPrefixes(String rubricText, List<MarkRow> marks) {
        Set<String> covered = coveredQuestionNumbers(rubricText);
        return marks.stream()
                .map(mark -> {
                    if (covered.contains(normalizeQuestionNumber(mark.q()))
                            || mark.explanation().stripLeading().toLowerCase(Locale.ROOT).startsWith("no rubric:")) {
                        return mark;
                    }
                    return new MarkRow(
                            mark.q(), mark.maxMarks(), mark.marksAwarded(), "No rubric: " + mark.explanation());
                })
                .toList();
    }

    private Set<String> coveredQuestionNumbers(String rubricText) {
        return rubricService.questionNumbersIn(rubricText).stream()
                .map(this::normalizeQuestionNumber)
                .collect(java.util.stream.Collectors.toSet());
    }

    private String normalizeQuestionNumber(String questionNumber) {
        String normalized = questionNumber.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("Q") ? normalized.substring(1) : normalized;
    }

    private List<MarkRow> readMarks(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            List<MarkRow> marks = new ArrayList<>();
            for (JsonNode mark : root) {
                marks.add(new MarkRow(
                        mark.path("q").asText(),
                        new BigDecimal(mark.path("max_marks").asText()),
                        new BigDecimal(mark.path("marks_awarded").asText()),
                        mark.path("explanation").asText()));
            }
            return List.copyOf(marks);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<String> readUnassigned(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            List<String> answers = new ArrayList<>();
            for (JsonNode answer : root) {
                answers.add(answer.asText());
            }
            return List.copyOf(answers);
        } catch (Exception exception) {
            return List.of();
        }
    }

    public record MarkRow(String q, BigDecimal maxMarks, BigDecimal marksAwarded, String explanation) {}

    public record MarkingRunSummary(
            UUID id,
            String runType,
            String provider,
            String modelName,
            String startedAt,
            boolean succeeded) {}

    public record MarkingRunView(
            UUID id,
            String provider,
            String modelName,
            String startedAt,
            boolean succeeded,
            List<MarkRow> marks,
            List<String> unassigned,
            String runType,
            String rubricText,
            List<String> questionsWithoutRubric,
            BigDecimal totalAwarded,
            String errorMessage) {}

    private record ParsedMarks(List<MarkRow> marks, List<String> unassigned) {}

    private static class InvalidModelReplyException extends RuntimeException {
        InvalidModelReplyException(String message) {
            super(message);
        }
    }
}
