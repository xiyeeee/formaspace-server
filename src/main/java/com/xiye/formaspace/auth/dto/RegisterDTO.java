package com.xiye.formaspace.auth.dto;

import com.xiye.formaspace.common.validation.*;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@PasswordsMatch
public class RegisterDTO {
    @EmailAddress
    private String email;
    @NotBlank
    @Pattern(regexp = "[a-zA-Z0-9_]{3,32}")
    private String username;
    @NotBlank
    @Pattern(regexp = "[0-9]{6}")
    private String verificationCode;
    @NotNull
    @Size(min = 8, max = 64)
    private String password;
    @NotNull
    @Size(min = 8, max = 64)
    private String confirmPassword;

    public void setEmail(String email) {
        this.email = AccountIdentifiers.normalize(email);
    }

    public void setUsername(String username) {
        this.username = AccountIdentifiers.normalize(username);
    }
}
