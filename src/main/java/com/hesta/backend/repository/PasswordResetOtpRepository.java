package com.hesta.backend.repository;

import com.hesta.backend.entity.PasswordResetOtp;
import com.hesta.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasswordResetOtpRepository extends JpaRepository<PasswordResetOtp, UUID> {
    
    // Tìm các OTP chưa sử dụng của một user, sắp xếp mới nhất lên đầu
    List<PasswordResetOtp> findByUserAndIsUsedFalseOrderByCreatedAtDesc(User user);
    
}
