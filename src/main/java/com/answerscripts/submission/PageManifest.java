package com.answerscripts.submission;

import java.util.UUID;
import java.util.List;

public record PageManifest(UUID examId, List<PageUploadEntry> pages) {}
