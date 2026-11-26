package com.answerscripts.questionpaper;

import java.util.UUID;

public record QuestionPaperOption(UUID id, UUID examId, String displayFilename, String uploadedAt, boolean replaced) {
}
