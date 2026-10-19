package at.bfnoessler.jobradar;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Was nicht in der Anzeige steht, ist {@code null}. Die Regeln greifen deshalb nur bei gesetzten
 * Werten — {@code null} ist fuer jedes Feld erlaubt. Die Meldungen gehen bei einem Verstoss
 * woertlich an das Modell zurueck.
 */
public record JobPosting(

		@Pattern(regexp = ".*\\S.*", message = "darf nicht leer sein") String company,

		@Size(min = 1, message = "muss mindestens einen Eintrag haben") //
		List<@NotBlank(message = "darf keinen leeren Eintrag enthalten") String> stack,

		@Min(value = 10_000, message = "ist das Jahresbrutto und muss mindestens 10000 sein, Monatsangaben mal 14") //
		@Max(value = 500_000, message = "darf hoechstens 500000 sein") //
		Integer salaryMinYearly,

		@Pattern(regexp = "remote|hybrid|onsite",
				message = "muss \"remote\", \"hybrid\" oder \"onsite\" sein") String remote,

		@Pattern(regexp = ".*\\S.*", message = "darf nicht leer sein") String location) {
}
