package com.schoolsoft.platform.db;

import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.NotFoundException;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Turns "the versioned UPDATE matched no row" into an answer the caller can act
 * on.
 *
 * <p>A versioned write is {@code ... WHERE id = ? AND version = ?}, and zero
 * rows affected has exactly two causes: the record is gone, or somebody else
 * saved first. They need different things done about them — one is a dead link,
 * the other is "reload and reapply your change" — so the distinction is worth
 * the extra read it costs on the failure path only.</p>
 */
public final class OptimisticLock {

    private OptimisticLock() {}

    /**
     * @param rowsAffected what the versioned UPDATE returned
     * @param what         the record, named as the caller would name it ("role", "fee structure")
     * @param id           the record's id, for the message
     * @param exists       tells whether the row is still there; called only when
     *                     {@code rowsAffected} is zero, so the happy path stays one statement
     */
    public static void requireApplied(int rowsAffected, String what, UUID id, Predicate<UUID> exists) {
        if (rowsAffected > 0) return;
        if (!exists.test(id)) {
            throw new NotFoundException(capitalise(what) + " not found: " + id);
        }
        throw new ConflictException(
            "This " + what + " was changed by somebody else while you were editing it. "
          + "Reload it and reapply your change — saving now would discard theirs.");
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
