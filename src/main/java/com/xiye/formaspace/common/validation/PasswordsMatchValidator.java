package com.xiye.formaspace.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Objects;
import org.springframework.beans.BeanWrapperImpl;

public class PasswordsMatchValidator implements ConstraintValidator<PasswordsMatch, Object> {
    private String passwordField;
    private String confirmationField;

    @Override
    public void initialize(PasswordsMatch annotation) {
        passwordField = annotation.passwordField();
        confirmationField = annotation.confirmationField();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        var properties = new BeanWrapperImpl(value);
        if (Objects.equals(properties.getPropertyValue(passwordField), properties.getPropertyValue(confirmationField))) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode(confirmationField).addConstraintViolation();
        return false;
    }
}
