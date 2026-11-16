package com.xiye.formaspace.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordsMatchValidator.class)
public @interface PasswordsMatch {
    String passwordField() default "password";
    String confirmationField() default "confirmPassword";
    String message() default "两次输入的密码必须一致";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
