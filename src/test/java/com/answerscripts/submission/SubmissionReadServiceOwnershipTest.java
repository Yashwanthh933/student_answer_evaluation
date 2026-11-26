package com.answerscripts.submission;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class SubmissionReadServiceOwnershipTest {

    @Test
    @SuppressWarnings("unchecked")
    void pageLookupRequiresBothSubmissionPageAndScannerOwner() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        UUID submissionId = UUID.randomUUID();
        String scannerUsername = "scanner-b";
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        SubmissionReadService service = new SubmissionReadService(jdbcTemplate);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.findPage(submissionId, 1, scannerUsername));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(query.capture(), any(RowMapper.class), parameters.capture());
        assertTrue(query.getValue().contains("scanner.username = ?"));
        assertArrayEquals(new Object[] {submissionId, 1, scannerUsername}, parameters.getValue());
    }
}
