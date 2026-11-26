package com.answerscripts.transcription;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.answerscripts.admin.ModelDefinition;
import com.answerscripts.admin.ModelSettingsService;
import com.answerscripts.storage.FileStorage;
import com.answerscripts.submission.SubmissionReadService;
import com.answerscripts.submission.SubmissionReadService.AdminPage;
import com.answerscripts.submission.SubmissionReadService.AdminSubmissionView;
import com.answerscripts.submission.SubmissionReadService.StoredPage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TranscriptionService {

    private static final int MAX_IMAGE_SIDE = 1600;

    private final JdbcTemplate jdbcTemplate;
    private final SubmissionReadService submissionReadService;
    private final FileStorage fileStorage;
    private final ModelSettingsService modelSettingsService;
    private final ModelProviderClient modelProviderClient;
    private final ObjectMapper objectMapper;
    private final Path instructionsFile;

    public TranscriptionService(
            JdbcTemplate jdbcTemplate,
            SubmissionReadService submissionReadService,
            FileStorage fileStorage,
            ModelSettingsService modelSettingsService,
            ModelProviderClient modelProviderClient,
            ObjectMapper objectMapper,
            @Value("${app.transcription.instructions-file}") String instructionsFile) {
        this.jdbcTemplate = jdbcTemplate;
        this.submissionReadService = submissionReadService;
        this.fileStorage = fileStorage;
        this.modelSettingsService = modelSettingsService;
        this.modelProviderClient = modelProviderClient;
        this.objectMapper = objectMapper;
        this.instructionsFile = Path.of(instructionsFile);
    }

    public List<SubmissionItem> listSubmissions() {
        return submissionReadService.listAll().stream()
                .map(submission -> new SubmissionItem(submission.submissionId(), submission.pageCount()))
                .toList();
    }

    public SubmissionDetail getSubmission(UUID submissionId) {
        List<PageView> pages = submissionReadService.pagesForAdmin(submissionId).stream()
                .map(page -> new PageView(
                        page.pageNumber(),
                        "/api/transcriptions/submissions/" + submissionId
                                + "/pages/" + page.pageNumber() + "/image"))
                .toList();
        List<RunSummary> runs = listRuns(submissionId);
        RunView latestRun = runs.isEmpty() ? null : findRun(submissionId, runs.get(0).id());
        return new SubmissionDetail(submissionId, pages, latestRun, runs);
    }

    public RunView getRun(UUID submissionId, UUID runId) {
        return findRun(submissionId, runId);
    }

    public RunView latestSuccessfulReading(UUID submissionId) {
        List<UUID> runIds = jdbcTemplate.query(
                "SELECT id FROM transcription_runs WHERE submission_id = ? AND succeeded = TRUE "
                        + "ORDER BY started_at DESC, id DESC LIMIT 1",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                submissionId);
        return runIds.isEmpty() ? null : findRun(submissionId, runIds.get(0));
    }

    public PageImage loadPageImage(UUID submissionId, int pageNumber) throws IOException {
        StoredPage page = submissionReadService.findPageForAdmin(submissionId, pageNumber);
        return new PageImage(page.contentType(), fileStorage.load(page.storedFilename()));
    }

    public RunView readPages(UUID submissionId) {
        List<AdminPage> pages = submissionReadService.pagesForAdmin(submissionId);
        if (pages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This submission has no pages to read.");
        }

        ModelSettingsService.SettingsView settings = modelSettingsService.getSettings();
        String selectedModelId = settings.selectedModelId();
        ModelSettingsService.ModelOption selected = settings.models().stream()
                .filter(model -> model.id().equals(selectedModelId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Choose a model in Model settings before reading pages."));
        ModelDefinition model = new ModelDefinition(
                selected.id(),
                selected.provider(),
                selected.modelName(),
                selected.endpoint(),
                selected.apiKeyEnv(),
                selected.canReadImages());

        UUID runId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO transcription_runs (id, submission_id, provider, model_name) VALUES (?, ?, ?, ?)",
                runId,
                submissionId,
                model.provider(),
                model.modelName());

        String rawReply = null;
        try {
            String apiKey = System.getenv(model.apiKeyEnv());
            if (apiKey == null || apiKey.isBlank()) {
                throw new ModelProviderClient.ModelCallException(
                        "The API key variable " + model.apiKeyEnv() + " is not set. Add it and try again.");
            }
            if (!model.canReadImages()) {
                throw new ModelProviderClient.ModelCallException("The selected model cannot read images.");
            }

            String instructions = Files.readString(instructionsFile);
            List<byte[]> resizedPages = new ArrayList<>();
            for (AdminPage page : pages) {
                resizedPages.add(toJpeg(page));
            }

            rawReply = modelProviderClient.readPages(model, apiKey, instructions, resizedPages);
            List<Answer> answers;
            try {
                answers = parseAnswers(rawReply);
            } catch (InvalidModelReplyException exception) {
                failRun(runId, rawReply, exception.getMessage());
                return findRun(runId);
            }

            String parsedAnswers = objectMapper.writeValueAsString(Map.of("answers", answers));
            jdbcTemplate.update(
                    "UPDATE transcription_runs SET succeeded = TRUE, raw_reply = ?, "
                            + "parsed_answers = ?::jsonb, error_message = NULL WHERE id = ?",
                    rawReply,
                    parsedAnswers,
                    runId);
        } catch (ModelProviderClient.ModelCallException exception) {
            failRun(runId, rawReply, exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failRun(runId, rawReply, "The model request was interrupted. You can try again.");
        } catch (IOException exception) {
            failRun(runId, rawReply, "Could not prepare the stored pages or read the instructions file.");
        } catch (Exception exception) {
            failRun(runId, rawReply, "The transcription could not be saved. You can try again.");
        }
        return findRun(runId);
    }

    private byte[] toJpeg(AdminPage page) throws IOException {
        StoredPage storedPage = new StoredPage(page.storedFilename(), page.contentType());
        BufferedImage source;
        try (var input = fileStorage.load(storedPage.storedFilename()).getInputStream()) {
            source = ImageIO.read(input);
        }
        if (source == null) {
            throw new IOException("Stored page is not a supported image.");
        }

        double scale = Math.min(1.0, (double) MAX_IMAGE_SIDE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage jpeg = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = jpeg.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(jpeg, "jpeg", output)) {
            throw new IOException("JPEG encoder is unavailable.");
        }
        return output.toByteArray();
    }

    private List<Answer> parseAnswers(String rawReply) {
        try {
            JsonNode root = objectMapper.readTree(rawReply);
            JsonNode answersNode = root.get("answers");
            if (answersNode == null || !answersNode.isArray()) {
                throw new InvalidModelReplyException("The model reply was not valid JSON with an answers list.");
            }

            List<Answer> answers = new ArrayList<>();
            for (JsonNode answer : answersNode) {
                JsonNode question = answer.get("q");
                JsonNode text = answer.get("text");
                if (question == null || !question.isTextual() || text == null || !text.isTextual()) {
                    throw new InvalidModelReplyException(
                            "The model reply did not match the required answers format. You can try again.");
                }
                answers.add(new Answer(question.asText(), text.asText()));
            }
            return List.copyOf(answers);
        } catch (InvalidModelReplyException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidModelReplyException("The model reply was not valid JSON. You can try again.");
        }
    }

    private void failRun(UUID runId, String rawReply, String errorMessage) {
        jdbcTemplate.update(
                "UPDATE transcription_runs SET succeeded = FALSE, raw_reply = ?, "
                        + "parsed_answers = NULL, error_message = ? WHERE id = ?",
                rawReply,
                errorMessage,
                runId);
    }

    private List<RunSummary> listRuns(UUID submissionId) {
        return jdbcTemplate.query(
                "SELECT id, provider, model_name, started_at, succeeded FROM transcription_runs "
                        + "WHERE submission_id = ? ORDER BY started_at DESC, id DESC",
                (resultSet, rowNumber) -> new RunSummary(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("provider"),
                        resultSet.getString("model_name"),
                        resultSet.getTimestamp("started_at").toInstant().toString(),
                        resultSet.getBoolean("succeeded")),
                submissionId);
    }

    private RunView findRun(UUID submissionId, UUID runId) {
        List<RunView> runs = jdbcTemplate.query(
                "SELECT id, provider, model_name, started_at, succeeded, parsed_answers::text AS parsed_json, "
                        + "error_message FROM transcription_runs WHERE submission_id = ? AND id = ?",
                (resultSet, rowNumber) -> new RunView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("provider"),
                        resultSet.getString("model_name"),
                        resultSet.getTimestamp("started_at").toInstant().toString(),
                        resultSet.getBoolean("succeeded"),
                        readStoredAnswers(resultSet.getString("parsed_json")),
                        resultSet.getString("error_message")),
                submissionId,
                runId);
        if (runs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Saved reading run not found.");
        }
        return runs.get(0);
    }

    private RunView findRun(UUID runId) {
        List<RunView> runs = jdbcTemplate.query(
                "SELECT id, submission_id, provider, model_name, started_at, succeeded, "
                        + "parsed_answers::text AS parsed_json, error_message "
                        + "FROM transcription_runs WHERE id = ?",
                (resultSet, rowNumber) -> new RunView(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("provider"),
                        resultSet.getString("model_name"),
                        resultSet.getTimestamp("started_at").toInstant().toString(),
                        resultSet.getBoolean("succeeded"),
                        readStoredAnswers(resultSet.getString("parsed_json")),
                        resultSet.getString("error_message")),
                runId);
        if (runs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Saved reading run not found.");
        }
        return runs.get(0);
    }

    private List<Answer> readStoredAnswers(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            List<Answer> answers = new ArrayList<>();
            for (JsonNode answer : root.path("answers")) {
                answers.add(new Answer(answer.path("q").asText(), answer.path("text").asText()));
            }
            return List.copyOf(answers);
        } catch (Exception exception) {
            return List.of();
        }
    }

    public record SubmissionItem(UUID submissionId, int pageCount) {}

    public record PageView(int pageNumber, String imageUrl) {}

    public record SubmissionDetail(
            UUID submissionId,
            List<PageView> pages,
            RunView latestRun,
            List<RunSummary> runs) {}

    public record RunSummary(
            UUID id,
            String provider,
            String modelName,
            String startedAt,
            boolean succeeded) {}

    public record Answer(String q, String text) {}

    public record RunView(
            UUID id,
            String provider,
            String modelName,
            String startedAt,
            boolean succeeded,
            List<Answer> answers,
            String errorMessage) {}

    public record PageImage(String contentType, Resource resource) {}

    private static class InvalidModelReplyException extends RuntimeException {
        InvalidModelReplyException(String message) {
            super(message);
        }
    }
}
