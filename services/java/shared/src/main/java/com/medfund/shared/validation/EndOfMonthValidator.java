package com.medfund.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.LocalDate;

public class EndOfMonthValidator implements ConstraintValidator<EndOfMonth, LocalDate> {
    @Override
    public boolean isValid(LocalDate value, ConstraintValidatorContext ctx) {
        // null → valid (see EndOfMonth javadoc). Callers add @NotNull if required.
        return value == null || value.getDayOfMonth() == value.lengthOfMonth();
    }
}
