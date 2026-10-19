package at.bfnoessler.jobradar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class DeepSeekExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(DeepSeekExceptionHandler.class);

	/** Erwartbarer Fehler: eine WARN-Zeile, kein Stacktrace. */
	@ExceptionHandler(DeepSeekException.class)
	ProblemDetail handle(DeepSeekException ex) {
		log.warn("Antwort {}: {}", ex.getStatus().value(), ex.getMessage());
		return ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
	}

}
