package com.xiye.formaspace.auth.dto;

import com.xiye.formaspace.common.validation.*;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginDTO {
    @NotBlank
    @Size(max = 254)
    private String account;
    @NotNull
    @Size(min = 8, max = 64)
    private String password;

    public void setAccount(String account) {
        this.account = AccountIdentifiers.normalize(account);
    }
}
