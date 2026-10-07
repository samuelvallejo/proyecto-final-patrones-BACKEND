package com.streamguard.core;

import com.streamguard.i18n.Messages;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ErrorHandler {
  @ExceptionHandler(ApiError.class)
  ResponseEntity<?> api(ApiError e) {
    return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<?> validation(MethodArgumentNotValidException e) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "error",
                e.getBindingResult().getFieldErrors().stream()
                    .map(this::validationMessage)
                    .findFirst()
                    .orElse(Messages.text("errorHandlerMessageText01"))));
  }

  private String validationMessage(FieldError error) {
    String code = error.getCode() == null ? "" : error.getCode();
    String messageKey;
    if (code.equals("Pattern") && error.getField().equals("slug")) messageKey = "validationSlug";
    else if (code.equals("Pattern") && error.getField().equals("email"))
      messageKey = "validationProviderEmail";
    else if (code.equals("Pattern") && error.getField().equals("username"))
      messageKey = "validationUsername";
    else
      messageKey =
          switch (code) {
            case "NotBlank", "NotNull", "NotEmpty" -> "validationRequired";
            case "Email" -> "validationEmail";
            case "Size" -> "validationSize";
            case "Pattern" -> "validationPattern";
            case "Min", "Max", "DecimalMin", "DecimalMax", "Positive", "PositiveOrZero" ->
                "validationNumber";
            default -> "errorHandlerMessageText02";
          };
    String fieldKey =
        switch (error.getField()) {
          case "username" -> "fieldUsername";
          case "email" -> "fieldEmail";
          case "password" -> "fieldPassword";
          case "slug" -> "fieldSlug";
          case "name" -> "fieldName";
          case "title" -> "fieldTitle";
          case "description" -> "fieldDescription";
          case "content" -> "fieldContent";
          case "text" -> "fieldText";
          case "reason" -> "fieldReason";
          case "aiConsent" -> "fieldAiConsent";
          case "start" -> "fieldStart";
          case "end" -> "fieldEnd";
          case "seconds" -> "fieldSeconds";
          case "level" -> "fieldLevel";
          case "type" -> "fieldType";
          case "source" -> "fieldSource";
          case "streamId" -> "fieldStreamId";
          case "userId" -> "fieldUserId";
          case "categoryId" -> "fieldCategoryId";
          default -> "fieldGeneric";
        };
    return Messages.text(fieldKey) + ": " + Messages.text(messageKey);
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(Map.of("error", Messages.text("errorHandlerMessageText02")));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(Exception e) {
    return ResponseEntity.status(409)
        .body(Map.of("error", Messages.text("errorHandlerMessageText03")));
  }
}
