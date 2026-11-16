package com.xiye.formaspace.auth.dto;

import com.xiye.formaspace.common.validation.*;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@PasswordsMatch(passwordField = "newPassword")
public class ResetPasswordDTO {
    @EmailAddress
    private String email;
    @NotBlank
    @Pattern(regexp = "[0-9]{6}")
    private String verificationCode;
    @NotNull
    @Size(min = 8, max = 64)
    private String newPassword;
    @NotNull
    @Size(min = 8, max = 64)
    private String confirmPassword;

    public void setEmail(String email) {
        this.email = AccountIdentifiers.normalize(email);
    }
}
