package com.xiye.formaspace.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.*;

@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@NotBlank
@Email
@Size(max = 254)
@Pattern(regexp = "[\\x21-\\x7E]+")
@ReportAsSingleViolation
public @interface EmailAddress {
    String message() default "邮箱格式不正确";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
