package com.stockflow.common;

import java.util.Map;
import java.util.Set;
import com.stockflow.common.error.ApiProblem;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;

/** Presence-aware PATCH: omitted is unchanged; null is permitted only for nullable fields. */
public final class PatchInput {
    private PatchInput() {}
    public static void check(Map<String, Object> patch, String... allowed) {
        if (patch == null || patch.isEmpty() || !Set.of(allowed).containsAll(patch.keySet())) invalid("Empty patch or unknown/immutable field.");
    }
    public static String text(Map<String, Object> patch, String field, String existing, boolean nullable) {
        if (!patch.containsKey(field)) return existing;
        Object value = patch.get(field);
        if (value == null && nullable) return null;
        if (!(value instanceof String)) invalid(field + " must be a string" + (nullable ? " or null." : "."));
        return (String) value;
    }
    public static boolean bool(Map<String, Object> patch, String field, boolean existing) {
        if (!patch.containsKey(field)) return existing;
        if (!(patch.get(field) instanceof Boolean)) invalid(field + " must be true or false.");
        return (Boolean) patch.get(field);
    }
    public static Integer integer(Map<String, Object> patch, String field, int existing) {
        if (!patch.containsKey(field)) return existing;
        if (!(patch.get(field) instanceof Integer)) invalid(field + " must be a whole number within integer bounds.");
        return (Integer) patch.get(field);
    }
    public static java.util.UUID uuid(Map<String, Object> patch, String field, java.util.UUID existing) {
        String value = text(patch, field, existing == null ? null : existing.toString(), true);
        try { return value == null ? null : java.util.UUID.fromString(value); }
        catch (IllegalArgumentException e) { invalid(field + " must be a UUID or null."); return null; }
    }
    public static <T> T validate(T value, Validator validator) {
        var violations = validator.validate(value);
        if (!violations.isEmpty()) throw new jakarta.validation.ConstraintViolationException(violations);
        return value;
    }
    private static void invalid(String message) { throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_PATCH", message); }
}
