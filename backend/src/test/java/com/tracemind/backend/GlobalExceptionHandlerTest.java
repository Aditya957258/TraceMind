
package com.tracemind.backend;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler();

    @Test
    void returnsBadRequestWithValidationErrorDetails() {
        MethodArgumentNotValidException exception =
                validationException(List.of(
                        new FieldError(
                                "request", "name", "Name is required"),
                        new FieldError(
                                "request", "repositoryUrl",
                                "Repository URL is required")
                ));

        var response = handler.handleValidationErrors(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(400, response.getBody().get("status"));
        assertEquals("Validation failed", response.getBody().get("error"));

        @SuppressWarnings("unchecked")
        Map<String, String> messages =
                (Map<String, String>) response.getBody().get("messages");

        assertEquals(2, messages.size());
        assertEquals("Name is required", messages.get("name"));
        assertEquals(
                "Repository URL is required",
                messages.get("repositoryUrl"));
    }

    @Test
    void preservesFirstMessageWhenFieldHasMultipleErrors() {
        MethodArgumentNotValidException exception =
                validationException(List.of(
                        new FieldError(
                                "request", "name", "Name is required"),
                        new FieldError(
                                "request", "name",
                                "Name must be shorter than 255 characters")
                ));

        var response = handler.handleValidationErrors(exception);

        assertNotNull(response.getBody());

        @SuppressWarnings("unchecked")
        Map<String, String> messages =
                (Map<String, String>) response.getBody().get("messages");

        assertEquals(1, messages.size());
        assertEquals("Name is required", messages.get("name"));
    }

    @Test
    void returnsEmptyMessagesWhenThereAreNoFieldErrors() {
        MethodArgumentNotValidException exception =
                validationException(List.of());

        var response = handler.handleValidationErrors(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());

        @SuppressWarnings("unchecked")
        Map<String, String> messages =
                (Map<String, String>) response.getBody().get("messages");

        assertTrue(messages.isEmpty());
    }

    private MethodArgumentNotValidException validationException(
            List<FieldError> fieldErrors) {
        var bindingResult = new BeanPropertyBindingResult(
                new Object(), "request");

        fieldErrors.forEach(bindingResult::addError);

        MethodArgumentNotValidException exception =
                mock(MethodArgumentNotValidException.class);

        when(exception.getBindingResult()).thenReturn(bindingResult);

        return exception;
    }
}
