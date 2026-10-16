package at.bfnoessler.jobradar;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ExtractController {

	private final DeepSeekClient deepSeekClient;

	ExtractController(DeepSeekClient deepSeekClient) {
		this.deepSeekClient = deepSeekClient;
	}

	@PostMapping(path = "/api/extract", consumes = MediaType.TEXT_PLAIN_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	JobPosting extract(@RequestBody String jobAd) {
		return this.deepSeekClient.extract(jobAd);
	}

}
