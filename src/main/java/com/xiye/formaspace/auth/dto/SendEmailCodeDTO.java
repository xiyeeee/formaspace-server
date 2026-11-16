package com.xiye.formaspace.auth.dto;

import com.xiye.formaspace.common.validation.*;
import com.xiye.formaspace.auth.enums.VerificationPurpose;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SendEmailCodeDTO {
    @EmailAddress
    private String email;
    @NotNull
    private VerificationPurpose purpose;

    public void setEmail(String email) {
        this.email = AccountIdentifiers.normalize(email);
    }
}
