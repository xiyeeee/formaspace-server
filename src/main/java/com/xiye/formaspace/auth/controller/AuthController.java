package com.xiye.formaspace.auth.controller;

import com.xiye.formaspace.auth.dto.*;
import com.xiye.formaspace.auth.security.CurrentUser;
import com.xiye.formaspace.auth.service.AuthService;
import com.xiye.formaspace.auth.service.EmailVerificationService;
import com.xiye.formaspace.auth.vo.*;
import com.xiye.formaspace.common.response.ApiResponse;
import com.xiye.formaspace.user.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService auth;
    private final EmailVerificationService verification;
    private final CurrentUser currentUser;

    @PostMapping("/email-codes")
    public ApiResponse<EmailCodeSendVO> emailCodes(@Valid @RequestBody SendEmailCodeDTO request, HttpServletRequest http) {
        return new ApiResponse<>("OK", "如可处理，验证码将发送至该邮箱，请查收",
                verification.send(request.getEmail(), request.getPurpose(), http.getRemoteAddr()));
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserVO>> register(@Valid @RequestBody RegisterDTO request, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(auth.register(request, http.getRemoteAddr())));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginVO>> login(@Valid @RequestBody LoginDTO request, HttpServletRequest http) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(auth.login(request, http.getRemoteAddr())));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserVO>> me() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(currentUser.require().user()));
    }
}
