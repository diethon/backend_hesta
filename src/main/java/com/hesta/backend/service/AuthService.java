package com.hesta.backend.service;

import com.hesta.backend.dto.request.GoogleLoginRequest;
import com.hesta.backend.dto.request.LoginRequest;
import com.hesta.backend.dto.request.RegisterRequest;
import com.hesta.backend.dto.response.AuthResponse;
import com.hesta.backend.dto.response.UserResponse;

public interface AuthService {
    UserResponse register(RegisterRequest request);
    AuthResponse login(LoginRequest request);
    AuthResponse loginWithGoogle(GoogleLoginRequest request);
}
