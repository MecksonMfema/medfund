package com.medfund.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link java.time.LocalDate} field as a *cycle-end* date — group
 * terminate {@code effectiveDate}, dependant deactivate {@code effectiveDate},
 * scheme {@code endDate}, tariff schedule {@code endDate}, pre-auth
 * {@code expiryDate}, override {@code effectiveTo}, and any other "when
 * does this state stop" input. Cycle-end dates must snap to the last day
 * of the month so the outgoing state stays billable through the whole
 * cycle it ends in — avoids a mid-cycle clip that flips arrears sign.
 *
 * <p>Null is treated as valid — a missing date means "server picks", not
 * "wrong shape". Combine with {@code @NotNull} where the field is required.
 *
 * <p>Companion: {@link FirstOfMonth} for cycle-start dates.
 */
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = EndOfMonthValidator.class)
public @interface EndOfMonth {
    String message() default "must be the last day of the month";

    Class<?>[] groups() default { };

    Class<? extends Payload>[] payload() default { };
}
