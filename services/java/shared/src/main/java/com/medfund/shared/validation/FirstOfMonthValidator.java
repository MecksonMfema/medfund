package com.medfund.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.LocalDate;

public class FirstOfMonthValidator implements ConstraintValidator<FirstOfMonth, LocalDate> {
    @Override
    public boolean isValid(LocalDate value, ConstraintValidatorContext ctx) {
        // null → valid (see FirstOfMonth javadoc). Callers add @NotNull if required.
        return value == null || value.getDayOfMonth() == 1;
    }
}
