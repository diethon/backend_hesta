package com.hesta.backend.service;

import com.hesta.backend.dto.request.RegisterRequest;
import com.hesta.backend.dto.response.UserResponse;

public interface AuthService {
    UserResponse register(RegisterRequest request);
}
