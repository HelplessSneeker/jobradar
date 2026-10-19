package at.bfnoessler.jobradar;

import java.net.http.HttpTimeoutException;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * DeepSeek wird nie live gerufen, alle Antworten kommen aus dem {@link MockRestServiceServer}.
 * Der Slice bringt keinen Validator mit, deshalb die {@link ValidationAutoConfiguration}.
 */
@RestClientTest(components = DeepSeekClient.class, properties = "deepseek.api-key=***")
@ImportAutoConfiguration(ValidationAutoConfiguration.class)
class DeepSeekClientTests {

	private static final String URL = "https://api.deepseek.com/chat/completions";

	private static final String ANZEIGE = "Java-Entwickler, Wien, ab 3.954 EUR brutto pro Monat, 14x";

	@Autowired
	private DeepSeekClient client;

	@Autowired
	private MockRestServiceServer server;

	/** Baut eine DeepSeek-Antwort, deren content das uebergebene JSON als String enthaelt. */
	private static String completion(String content) {
		return """
				{"choices": [{"message": {"role": "assistant", "content": "%s"}, "finish_reason": "stop"}],
				 "usage": {"prompt_tokens": 1937, "prompt_cache_hit_tokens": 1792, "completion_tokens": 95}}
				""".formatted(content.replace("\"", "\\\""));
	}

	private void expectCompletion(String content) {
		this.server.expect(requestTo(URL))
				.andRespond(withSuccess(completion(content), MediaType.APPLICATION_JSON));
	}

	@Test
	void gueltigeAntwortWirdDurchgereicht() {
		expectCompletion("""
				{"company": "p.how GmbH", "stack": ["Java", "Spring Boot"], "salaryMinYearly": 55356, \
				"remote": "hybrid", "location": "Wien"}""");

		JobPosting posting = this.client.extract(ANZEIGE);

		assertThat(posting)
				.isEqualTo(new JobPosting("p.how GmbH", List.of("Java", "Spring Boot"), 55356, "hybrid", "Wien"));
		this.server.verify();
	}

	@Test
	void vergessenesMalVierzehnWirdNachgebessert() {
		// 3954 ist das Monatsgehalt und faellt unter @Min(10000).
		expectCompletion("""
				{"company": "p.how GmbH", "stack": ["Java"], "salaryMinYearly": 3954, \
				"remote": "hybrid", "location": "Wien"}""");
		this.server.expect(ExpectedCount.once(), requestTo(URL))
				.andExpect(content().string(containsString("\"assistant\"")))
				.andExpect(content().string(containsString("salaryMinYearly ist das Jahresbrutto")))
				.andExpect(content().string(containsString("war: 3954")))
				.andExpect(content().string(containsString("Schick nur das korrigierte JSON-Objekt")))
				.andRespond(withSuccess(completion("""
						{"company": "p.how GmbH", "stack": ["Java"], "salaryMinYearly": 55356, \
						"remote": "hybrid", "location": "Wien"}"""), MediaType.APPLICATION_JSON));

		JobPosting posting = this.client.extract(ANZEIGE);

		assertThat(posting.salaryMinYearly()).isEqualTo(55356);
		// verify() faellt durch, wenn es nicht genau diese zwei Requests waren.
		this.server.verify();
	}

	@Test
	void zweimalFalschesRemoteWirdNullUndDerRestBleibt() {
		String antwort = """
				{"company": "p.how GmbH", "stack": ["Java"], "salaryMinYearly": 55356, \
				"remote": "teilweise", "location": "Wien"}""";
		expectCompletion(antwort);
		expectCompletion(antwort);

		JobPosting posting = this.client.extract(ANZEIGE);

		assertThat(posting.remote()).isNull();
		assertThat(posting.company()).isEqualTo("p.how GmbH");
		assertThat(posting.stack()).containsExactly("Java");
		assertThat(posting.salaryMinYearly()).isEqualTo(55356);
		assertThat(posting.location()).isEqualTo("Wien");
		this.server.verify();
	}

	@Test
	void zweimalLeererInhaltGibt502() {
		expectCompletion("");
		expectCompletion("");

		assertThatExceptionOfType(DeepSeekException.class)
				.isThrownBy(() -> this.client.extract(ANZEIGE))
				.satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
		this.server.verify();
	}

	@Test
	void abgeschnitteneAntwortGiltAlsUnbrauchbar() {
		String abgeschnitten = """
				{"choices": [{"message": {"role": "assistant", "content": "{\\"company\\": \\"p.how"},
				  "finish_reason": "length"}],
				 "usage": {"prompt_tokens": 1937, "prompt_cache_hit_tokens": 0, "completion_tokens": 500}}
				""";
		this.server.expect(ExpectedCount.twice(), requestTo(URL))
				.andRespond(withSuccess(abgeschnitten, MediaType.APPLICATION_JSON));

		assertThatExceptionOfType(DeepSeekException.class)
				.isThrownBy(() -> this.client.extract(ANZEIGE))
				.satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
		this.server.verify();
	}

	@Test
	void abgelehnterApiKeyGibt502MitHinweisAufDieEnv() {
		this.server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertThatExceptionOfType(DeepSeekException.class)
				.isThrownBy(() -> this.client.extract(ANZEIGE))
				.satisfies(ex -> {
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
					assertThat(ex.getMessage()).contains("DEEPSEEK_API_KEY");
				});
	}

	@Test
	void aufgebrauchtesGuthabenGibt502() {
		this.server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED));

		assertThatExceptionOfType(DeepSeekException.class)
				.isThrownBy(() -> this.client.extract(ANZEIGE))
				.satisfies(ex -> {
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
					assertThat(ex.getMessage()).contains("Guthaben");
				});
	}

	@Test
	void ueberlastungGibt503() {
		this.server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		assertThatExceptionOfType(DeepSeekException.class)
				.isThrownBy(() -> this.client.extract(ANZEIGE))
				.satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
	}

	@Test
	void timeoutGibt504() {
		this.server.expect(requestTo(URL)).andRespond(withException(new HttpTimeoutException("request timed out")));

		assertThatExceptionOfType(DeepSeekException.class)
				.isThrownBy(() -> this.client.extract(ANZEIGE))
				.satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT));
	}

}
