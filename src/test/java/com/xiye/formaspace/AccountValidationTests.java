package com.xiye.formaspace;

import com.xiye.formaspace.auth.dto.*;
import com.xiye.formaspace.common.response.PageQuery;
import com.xiye.formaspace.generation.dto.CreateGenerationDTO;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AccountValidationTests {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    private RegisterDTO validRegistration() {
        var request = new RegisterDTO();
        request.setEmail("  First.Last+Tag@Example.COM  ");
        request.setUsername("  User_1  ");
        request.setVerificationCode("012345");
        request.setPassword(" password ");
        request.setConfirmPassword(" password ");
        return request;
    }

    @Test
    void normalizesIdentifiersAndPreservesPasswordDotsAndPlus() {
        var request = validRegistration();
        assertThat(VALIDATOR.validate(request)).isEmpty();
        assertThat(request.getEmail()).isEqualTo("first.last+tag@example.com");
        assertThat(request.getUsername()).isEqualTo("user_1");
        assertThat(request.getPassword()).isEqualTo(" password ");
        assertThat(request.toString()).doesNotContain("password", "012345");
    }

    @Test
    void passwordConfirmationAppliesToRegistrationAndReset() {
        var request = validRegistration();
        request.setConfirmPassword("different");
        assertThat(VALIDATOR.validate(request)).anyMatch(violation ->
                violation.getPropertyPath().toString().equals("confirmPassword"));
        var reset = new ResetPasswordDTO();
        reset.setEmail("user@example.com");
        reset.setVerificationCode("123456");
        reset.setNewPassword("new-password");
        reset.setConfirmPassword("new-password");
        assertThat(VALIDATOR.validate(reset)).isEmpty();
        reset.setConfirmPassword("mismatched");
        assertThat(VALIDATOR.validate(reset)).anyMatch(violation ->
                violation.getPropertyPath().toString().equals("confirmPassword"));
        assertThat(reset.toString()).doesNotContain("new-password", "123456");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "contains@symbol", "with-hyphen", "中文用户名", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void rejectsInvalidUsernames(String username) {
        var request = validRegistration();
        request.setUsername(username);
        assertThat(VALIDATOR.validate(request)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "用户@example.com", "user@例子.com", "a b@example.com"})
    void rejectsInvalidOrNonAsciiEmails(String email) {
        var request = validRegistration();
        request.setEmail(email);
        assertThat(VALIDATOR.validate(request)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "1234567", "abcdef", "１２３４５６"})
    void requiresSixAsciiDigits(String code) {
        var request = validRegistration();
        request.setVerificationCode(code);
        assertThat(VALIDATOR.validate(request)).isNotEmpty();
    }

    @Test
    void passwordLengthBoundariesDoNotRequireCharacterCombinations() {
        var request = validRegistration();
        for (int length : new int[] {7, 8, 64, 65}) {
            request.setPassword("a".repeat(length));
            request.setConfirmPassword(request.getPassword());
            assertThat(VALIDATOR.validate(request).isEmpty()).isEqualTo(length >= 8 && length <= 64);
        }
        request.setPassword(null);
        request.setConfirmPassword(null);
        assertThat(VALIDATOR.validate(request)).isNotEmpty();
    }

    @Test
    void allRegistrationFieldsRequiredAndSendPurposeRequired() {
        assertThat(VALIDATOR.validate(new RegisterDTO())).hasSize(5);
        var send = new SendEmailCodeDTO();
        send.setEmail("user@example.com");
        assertThat(VALIDATOR.validate(send)).anyMatch(violation ->
                violation.getPropertyPath().toString().equals("purpose"));
    }

    @Test
    void loginUsesNormalizedAccountAndPreservesPassword() {
        var request = new LoginDTO();
        request.setAccount(" NAME@EXAMPLE.COM ");
        request.setPassword(" password ");
        assertThat(VALIDATOR.validate(request)).isEmpty();
        assertThat(request.getAccount()).isEqualTo("name@example.com");
        assertThat(request.getPassword()).isEqualTo(" password ");
    }

    @Test
    void paginationHasDefaultsAndLimits() {
        var query = new PageQuery();
        assertThat(query.getPage()).isEqualTo(1);
        assertThat(query.getSize()).isEqualTo(20);
        query.setPage(0);
        query.setSize(101);
        assertThat(VALIDATOR.validate(query)).hasSize(2);
        query.setPage(1);
        query.setSize(100);
        assertThat(VALIDATOR.validate(query)).isEmpty();
        query.setSize(0);
        assertThat(VALIDATOR.validate(query)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "9223372036854775808", "01"})
    void rejectsSourceFileIdsOutsideCanonicalPositiveLongRange(String value) {
        var request = new CreateGenerationDTO();
        request.setSourceFileId(value);
        assertThat(VALIDATOR.validate(request)).isNotEmpty();
    }

    @Test
    void acceptsMaxLongAsString() {
        var request = new CreateGenerationDTO();
        request.setSourceFileId("9223372036854775807");
        assertThat(VALIDATOR.validate(request)).isEmpty();
    }
}
