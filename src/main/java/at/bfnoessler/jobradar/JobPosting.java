package at.bfnoessler.jobradar;

import java.util.List;

public record JobPosting(
		String company,
		List<String> stack,
		Integer salaryMinYearly,
		String remote,
		String location) {
}
