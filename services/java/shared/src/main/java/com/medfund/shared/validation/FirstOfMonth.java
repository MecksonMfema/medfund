package com.medfund.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link java.time.LocalDate} field as a *cycle-start* date —
 * enrolments, group changes, scheme changes, swap effectiveFrom, override
 * effectiveFrom, and any other "when does this new state begin" input.
 * Cycle-start dates must snap to the 1st of the month; billing math
 * otherwise miscomputes arrears on backdated changes.
 *
 * <p>Null is treated as valid — a missing date means "server picks", not
 * "wrong shape". Combine with {@code @NotNull} where the field is required.
 *
 * <p>Companion: {@link EndOfMonth} for termination-shaped dates.
 */
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = FirstOfMonthValidator.class)
public @interface FirstOfMonth {
    String message() default "must be the 1st of the month";

    Class<?>[] groups() default { };

    Class<? extends Payload>[] payload() default { };
}
