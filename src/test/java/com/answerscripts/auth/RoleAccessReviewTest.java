package com.answerscripts.auth;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.answerscripts.storage.FileStorage;
import com.answerscripts.submission.SubmissionReadService;
import com.answerscripts.user.AccountPrincipal;
import com.answerscripts.user.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(controllers = com.answerscripts.submission.SubmissionReadController.class)
@Import(SecurityConfiguration.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class RoleAccessReviewTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserAccountService userAccountService;

    @MockitoBean
    private SubmissionReadService submissionReadService;

    @MockitoBean
    private FileStorage fileStorage;

    @Test
    void scannerCannotOpenExamUserSettingsOrMarkingPages() throws Exception {
        for (String path : new String[] {
                "/exams.html", "/api/exams", "/question-paper.html", "/api/question-papers/exams",
                "/rubric.html", "/api/rubrics/exams", "/users.html", "/api/users",
                "/settings.html", "/api/settings", "/transcription.html", "/api/transcriptions/submissions",
                "/api/marking/submissions/" + UUID.randomUUID() + "/inputs"
        }) {
            mockMvc.perform(get(path).with(user("scanner-a").roles("SCANNER")))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void examControllerCannotOpenUserModelSettingsOrMarkingPages() throws Exception {
        for (String path : new String[] {
                "/users.html", "/api/users", "/settings.html", "/api/settings",
                "/transcription.html", "/api/transcriptions/submissions",
                "/api/marking/submissions/" + UUID.randomUUID() + "/inputs"
        }) {
            mockMvc.perform(get(path).with(user("controller").roles("EXAM_CONTROLLER")))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void disabledUserSessionIsRefusedOnTheNextRequest() throws Exception {
        when(userAccountService.accountState("disabled-scanner"))
                .thenReturn(new UserAccountService.AccountState(false, false));

        mockMvc.perform(get("/submissions.html").with(user(
                        new AccountPrincipal("disabled-scanner", "unused", "SCANNER", true, false))))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void pageImageRequestUsesSignedInScannerForOwnershipCheck() throws Exception {
        UUID submissionId = UUID.randomUUID();
        when(submissionReadService.findPage(submissionId, 1, "scanner-b"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission page not found."));

        mockMvc.perform(get("/api/submissions/{submissionId}/pages/1/image", submissionId)
                        .with(user("scanner-b").roles("SCANNER")))
                .andExpect(status().isNotFound());

        verify(submissionReadService).findPage(submissionId, 1, "scanner-b");
    }
}
