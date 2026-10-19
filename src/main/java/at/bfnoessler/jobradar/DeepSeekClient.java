package at.bfnoessler.jobradar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
class DeepSeekClient {

	private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

	private static final String SYSTEM_PROMPT = """
			Du extrahierst Daten aus Stellenanzeigen und antwortest ausschliesslich mit einem JSON-Objekt \
			in genau diesem Format:
			{"company": "Muster Software GmbH", "stack": ["Python", "Django"], "salaryMinYearly": 48000, "remote": "onsite", "location": "Graz"}
			Regeln:
			- Was nicht in der Anzeige steht, ist null. Nichts raten, nichts erfinden.
			- salaryMinYearly ist das Jahresbrutto in EUR als ganze Zahl; Monatsangaben mal 14.
			- remote ist genau einer der Werte "remote", "hybrid", "onsite" oder null.
			""";

	private final RestClient restClient;

	private final ObjectMapper objectMapper;

	private final Validator validator;

	private final double inputPrice;

	private final double cacheHitPrice;

	private final double outputPrice;

	DeepSeekClient(RestClient.Builder builder, ObjectMapper objectMapper, Validator validator,
			@Value("${deepseek.api-key}") String apiKey,
			@Value("${deepseek.price.input}") double inputPrice,
			@Value("${deepseek.price.cache-hit}") double cacheHitPrice,
			@Value("${deepseek.price.output}") double outputPrice) {
		this.restClient = builder
				.baseUrl("https://api.deepseek.com")
				.defaultHeader("Authorization", "Bearer " + apiKey)
				.build();
		this.objectMapper = objectMapper;
		this.validator = validator;
		this.inputPrice = inputPrice;
		this.cacheHitPrice = cacheHitPrice;
		this.outputPrice = outputPrice;
	}

	/**
	 * Hoechstens zwei Aufrufe: einer fuer die Anzeige, bei einer unbrauchbaren Antwort oder einem
	 * Regelverstoss einer zum Nachbessern. Was danach immer noch gegen seine Regel verstoesst, wird
	 * {@code null}.
	 */
	JobPosting extract(String jobAd) {
		List<Message> prompt = List.of(new Message("system", SYSTEM_PROMPT), new Message("user", jobAd));

		String content = usableContent(call(prompt));
		JobPosting posting = (content != null) ? parse(content) : null;

		if (posting == null) {
			// Unbrauchbar: denselben Request noch einmal.
			posting = retry(prompt);
		}
		else {
			Set<ConstraintViolation<JobPosting>> violations = this.validator.validate(posting);
			if (!violations.isEmpty()) {
				// Regelverstoss: System-Prompt und Anzeige bleiben vorne stehen, damit der
				// DeepSeek-Cache greift; hinten kommt die eigene Antwort und die Korrekturbitte dazu.
				List<Message> correction = new ArrayList<>(prompt);
				correction.add(new Message("assistant", content));
				correction.add(new Message("user", correctionPrompt(violations)));
				posting = retry(correction);
			}
		}

		Set<ConstraintViolation<JobPosting>> remaining = this.validator.validate(posting);
		return remaining.isEmpty() ? posting : dropViolations(posting, remaining);
	}

	private JobPosting retry(List<Message> messages) {
		String content = usableContent(call(messages));
		JobPosting posting = (content != null) ? parse(content) : null;
		if (posting == null) {
			throw new DeepSeekException(HttpStatus.BAD_GATEWAY,
					"DeepSeek liefert auch im zweiten Versuch keine brauchbare Antwort");
		}
		return posting;
	}

	private ChatResponse call(List<Message> messages) {
		ChatResponse response;
		try {
			response = this.restClient.post()
					.uri("/chat/completions")
					.body(new ChatRequest(
							"deepseek-flash",
							messages,
							new Thinking("disabled"),
							new ResponseFormat("json_object"),
							500))
					.retrieve()
					.body(ChatResponse.class);
		}
		catch (RestClientResponseException ex) {
			throw translate(ex);
		}
		catch (ResourceAccessException ex) {
			throw new DeepSeekException(HttpStatus.GATEWAY_TIMEOUT,
					"DeepSeek ist nicht erreichbar oder antwortet nicht rechtzeitig", ex);
		}

		if (response != null && response.usage() != null) {
			Usage usage = response.usage();
			log.info("DeepSeek usage: prompt_tokens={} prompt_cache_hit_tokens={} completion_tokens={} cost={} USD",
					usage.promptTokens(), usage.promptCacheHitTokens(), usage.completionTokens(), cost(usage));
		}
		return response;
	}

	/** Spring hat fuer 402 keine eigene Exception-Klasse, deshalb geht alles ueber den Statuscode. */
	private DeepSeekException translate(RestClientResponseException ex) {
		int status = ex.getStatusCode().value();
		return switch (status) {
			case 401 -> new DeepSeekException(HttpStatus.BAD_GATEWAY,
					"DeepSeek lehnt den API-Key ab, DEEPSEEK_API_KEY in .env prüfen", ex);
			case 402 -> new DeepSeekException(HttpStatus.BAD_GATEWAY,
					"DeepSeek-Guthaben ist aufgebraucht", ex);
			case 429, 503 -> new DeepSeekException(HttpStatus.SERVICE_UNAVAILABLE,
					"DeepSeek ist überlastet, später noch einmal versuchen", ex);
			default -> new DeepSeekException(HttpStatus.BAD_GATEWAY,
					"DeepSeek antwortet mit Status " + status, ex);
		};
	}

	/** Der verwertbare Inhalt der Antwort, oder {@code null}, wenn sie leer oder abgeschnitten ist. */
	private String usableContent(ChatResponse response) {
		if (response == null || response.choices() == null || response.choices().isEmpty()) {
			log.warn("DeepSeek-Antwort enthält keine Choice");
			return null;
		}
		Choice choice = response.choices().getFirst();
		if ("length".equals(choice.finishReason())) {
			log.warn("DeepSeek-Antwort ist abgeschnitten (finish_reason=length)");
			return null;
		}
		String content = (choice.message() != null) ? choice.message().content() : null;
		if (content == null || content.isBlank()) {
			log.warn("DeepSeek-Antwort hat keinen Inhalt");
			return null;
		}
		return content;
	}

	/** {@code null}, wenn das JSON nicht auf {@link JobPosting} passt. */
	private JobPosting parse(String content) {
		try {
			return this.objectMapper.readValue(content, JobPosting.class);
		}
		catch (JacksonException ex) {
			log.warn("DeepSeek-Antwort passt nicht auf JobPosting: {}", ex.getOriginalMessage());
			return null;
		}
	}

	private String correctionPrompt(Set<ConstraintViolation<JobPosting>> violations) {
		StringBuilder prompt = new StringBuilder("Dein JSON verletzt diese Regeln:\n");
		violations.stream()
				.map(violation -> "- %s %s, war: %s\n".formatted(violation.getPropertyPath(),
						violation.getMessage(), violation.getInvalidValue()))
				.sorted()
				.forEach(prompt::append);
		return prompt.append("Schick nur das korrigierte JSON-Objekt, sonst nichts.").toString();
	}

	private JobPosting dropViolations(JobPosting posting, Set<ConstraintViolation<JobPosting>> violations) {
		String company = posting.company();
		List<String> stack = posting.stack();
		Integer salaryMinYearly = posting.salaryMinYearly();
		String remote = posting.remote();
		String location = posting.location();
		for (ConstraintViolation<JobPosting> violation : violations) {
			String field = rootField(violation);
			log.warn("Feld verstößt auch im zweiten Versuch gegen seine Regel und wird null: {}={}",
					field, violation.getInvalidValue());
			switch (field) {
				case "company" -> company = null;
				case "stack" -> stack = null;
				case "salaryMinYearly" -> salaryMinYearly = null;
				case "remote" -> remote = null;
				case "location" -> location = null;
				default -> log.warn("Unbekanntes Feld in der Verletzung: {}", field);
			}
		}
		return new JobPosting(company, stack, salaryMinYearly, remote, location);
	}

	/** Aus {@code stack[0].<list element>} wird {@code stack} — genullt wird immer das ganze Feld. */
	private String rootField(ConstraintViolation<JobPosting> violation) {
		return violation.getPropertyPath().iterator().next().getName();
	}

	/** Cache-Treffer sind billiger, der Rest der Prompt-Tokens zahlt den vollen Eingabepreis. */
	private String cost(Usage usage) {
		int uncachedPromptTokens = usage.promptTokens() - usage.promptCacheHitTokens();
		double usd = (uncachedPromptTokens * this.inputPrice
				+ usage.promptCacheHitTokens() * this.cacheHitPrice
				+ usage.completionTokens() * this.outputPrice) / 1_000_000;
		return String.format(Locale.ROOT, "%.6f", usd);
	}

	record ChatRequest(String model, List<Message> messages, Thinking thinking,
			@JsonProperty("response_format") ResponseFormat responseFormat,
			@JsonProperty("max_tokens") int maxTokens) {
	}

	record Message(String role, String content) {
	}

	record Thinking(String type) {
	}

	record ResponseFormat(String type) {
	}

	record ChatResponse(List<Choice> choices, Usage usage) {
	}

	record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {
	}

	record Usage(@JsonProperty("prompt_tokens") int promptTokens,
			@JsonProperty("prompt_cache_hit_tokens") int promptCacheHitTokens,
			@JsonProperty("completion_tokens") int completionTokens) {
	}

}
