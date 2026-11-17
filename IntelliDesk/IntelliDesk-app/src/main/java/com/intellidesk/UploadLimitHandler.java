package com.intellidesk;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
/** Spring rejects oversized uploads before any controller runs; return a clear message instead of a generic error. */
@RestControllerAdvice
public class UploadLimitHandler {
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<Map<String,String>> tooBig() {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("message", "Files must be 10 MB or smaller"));
  }
}
