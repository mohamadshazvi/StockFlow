package com.stockflow.common;

import java.util.Set;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import com.stockflow.common.error.ApiProblem;

public final class ListQuery {
    private ListQuery() {}
    public static PageRequest page(int page, int size, String sort, Set<String> fields) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) invalid("Use a nonnegative page and size 1–100.");
        String[] parts = sort.split(",", -1);
        if (parts.length != 2 || !fields.contains(parts[0]) || !Set.of("asc", "desc").contains(parts[1])) invalid("Invalid sort field or direction.");
        return PageRequest.of(page, size, Sort.by(Sort.Direction.fromString(parts[1]), parts[0]).and(Sort.by("id")));
    }
    public static Boolean active(String value) {
        return switch (value) { case "true" -> true; case "false" -> false; case "all" -> null; default -> throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "active must be true, false or all."); };
    }
    public static String term(String search) {
        if (search.length() > 160) invalid("Search must not exceed 160 characters.");
        return "%" + search.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
    private static void invalid(String message) { throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_QUERY", message); }
}
