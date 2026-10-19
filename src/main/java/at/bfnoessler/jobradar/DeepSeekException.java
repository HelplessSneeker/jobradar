package at.bfnoessler.jobradar;

import org.springframework.http.HttpStatusCode;

/** Traegt den Status, den <em>wir</em> antworten — nicht den, den DeepSeek geschickt hat. */
class DeepSeekException extends RuntimeException {

	private final HttpStatusCode status;

	DeepSeekException(HttpStatusCode status, String message) {
		super(message);
		this.status = status;
	}

	DeepSeekException(HttpStatusCode status, String message, Throwable cause) {
		super(message, cause);
		this.status = status;
	}

	HttpStatusCode getStatus() {
		return this.status;
	}

}
