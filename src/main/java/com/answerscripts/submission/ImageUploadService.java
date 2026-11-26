package com.answerscripts.submission;

import com.answerscripts.storage.FileStorage;
import com.answerscripts.exam.ExamService;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImageUploadService {

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final Pattern FILE_PART_PATTERN = Pattern.compile("image-[0-9a-f]{32}");

    private final FileStorage fileStorage;
    private final JdbcTemplate jdbcTemplate;
    private final ExamService examService;

    public ImageUploadService(FileStorage fileStorage, JdbcTemplate jdbcTemplate, ExamService examService) {
        this.fileStorage = fileStorage;
        this.jdbcTemplate = jdbcTemplate;
        this.examService = examService;
    }

    @Transactional
    public UUID upload(
            UUID examId,
            List<PageUploadEntry> manifest,
            MultiValueMap<String, MultipartFile> imageParts,
            String scannerUsername) {
        if (examId == null) throw badRequest("Choose an open exam before submitting pages.");
        examService.requireOpenExam(examId);
        UUID scannerUserId = examService.enabledUserId(scannerUsername);
        List<IncomingPage> pages = validate(manifest, imageParts);
        UUID submissionId = UUID.randomUUID();
        List<StoredPage> storedPages = new ArrayList<>();

        try {
            for (IncomingPage page : pages) {
                try (InputStream input = page.image().getInputStream()) {
                    String storedFilename = fileStorage.store(input, page.format().extension());
                    storedPages.add(new StoredPage(page.pageNumber(), storedFilename, page.format().contentType()));
                }
            }

            jdbcTemplate.update(
                    "INSERT INTO answer_submissions (id, scanner_user_id, exam_id) VALUES (?, ?, ?)",
                    submissionId,
                    scannerUserId,
                    examId);
            for (StoredPage page : storedPages) {
                jdbcTemplate.update(
                        "INSERT INTO answer_pages (id, submission_id, page_number, stored_filename, content_type) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        UUID.randomUUID(),
                        submissionId,
                        page.pageNumber(),
                        page.storedFilename(),
                        page.contentType());
            }
        } catch (IOException exception) {
            deleteStoredFiles(storedPages, exception);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store the images.", exception);
        } catch (RuntimeException exception) {
            deleteStoredFiles(storedPages, exception);
            throw exception;
        }

        return submissionId;
    }

    private List<IncomingPage> validate(
            List<PageUploadEntry> manifest,
            MultiValueMap<String, MultipartFile> imageParts) {
        if (manifest == null || manifest.isEmpty()) {
            throw badRequest("Add at least one image before submitting.");
        }
        if (imageParts == null || imageParts.size() != manifest.size()) {
            throw badRequest("Each page number must reference exactly one image.");
        }

        Set<String> partNames = new HashSet<>();
        Set<Integer> pageNumbers = new HashSet<>();
        List<IncomingPage> pages = new ArrayList<>();

        for (PageUploadEntry entry : manifest) {
            if (entry == null || entry.pageNumber() < 1 || !pageNumbers.add(entry.pageNumber())) {
                throw badRequest("Page numbers must be unique positive integers.");
            }
            if (entry.filePart() == null
                    || !FILE_PART_PATTERN.matcher(entry.filePart()).matches()
                    || !partNames.add(entry.filePart())) {
                throw badRequest("Each page must reference one valid image part.");
            }

            List<MultipartFile> matchingParts = imageParts.get(entry.filePart());
            if (matchingParts == null || matchingParts.size() != 1 || matchingParts.get(0).isEmpty()) {
                throw badRequest("Each page number must reference one non-empty image.");
            }

            MultipartFile image = matchingParts.get(0);
            pages.add(new IncomingPage(entry.pageNumber(), image, detectFormat(image)));
        }

        if (!partNames.equals(imageParts.keySet())) {
            throw badRequest("The image parts do not match the page manifest.");
        }
        for (int expected = 1; expected <= manifest.size(); expected++) {
            if (!pageNumbers.contains(expected)) {
                throw badRequest("Page numbers must start at 1 and have no gaps.");
            }
        }

        return pages;
    }

    private ImageFormat detectFormat(MultipartFile image) {
        try (InputStream input = image.getInputStream()) {
            byte[] signature = input.readNBytes(PNG_SIGNATURE.length);

            // Check only the file signature; the uploaded image is not inspected or analyzed.
            if (Arrays.equals(signature, PNG_SIGNATURE)) {
                return ImageFormat.PNG;
            }
            if (signature.length >= 3
                    && signature[0] == (byte) 0xff
                    && signature[1] == (byte) 0xd8
                    && signature[2] == (byte) 0xff) {
                return ImageFormat.JPEG;
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read an uploaded image.", exception);
        }

        throw badRequest("Upload JPEG or PNG images.");
    }

    private void deleteStoredFiles(List<StoredPage> pages, Exception originalException) {
        for (StoredPage page : pages) {
            try {
                fileStorage.delete(page.storedFilename());
            } catch (IOException cleanupException) {
                originalException.addSuppressed(cleanupException);
            }
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record IncomingPage(int pageNumber, MultipartFile image, ImageFormat format) {}

    private record StoredPage(int pageNumber, String storedFilename, String contentType) {}

    private enum ImageFormat {
        JPEG("image/jpeg", ".jpg"),
        PNG("image/png", ".png");

        private final String contentType;
        private final String extension;

        ImageFormat(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        String contentType() {
            return contentType;
        }

        String extension() {
            return extension;
        }
    }
}
