package com.hesta.backend.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final String fromEmail;

    public EmailService(JavaMailSender mailSender,
                        @Value("${spring.mail.username}") String fromEmail) {
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
    }

    public void sendPasswordResetOtp(String toEmail, String otp) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("HESTA Smart Home - Mã xác thực khôi phục mật khẩu");

            String htmlContent = "<div style=\"font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; max-width: 600px; margin: 0 auto; background-color: #f9fafb; padding: 40px 20px; border-radius: 12px;\">" +
                    "   <div style=\"background-color: #ffffff; padding: 40px; border-radius: 12px; box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06); text-align: center;\">" +
                    "       <h1 style=\"color: #0f172a; margin-top: 0; font-size: 24px; font-weight: 700;\">Khôi phục mật khẩu</h1>" +
                    "       <p style=\"color: #475569; font-size: 16px; line-height: 1.6; text-align: left;\">Xin chào,</p>" +
                    "       <p style=\"color: #475569; font-size: 16px; line-height: 1.6; text-align: left;\">Bạn vừa yêu cầu khôi phục mật khẩu cho tài khoản <strong>HESTA Smart Home</strong> của mình. Dưới đây là mã xác thực (OTP) dành cho bạn:</p>" +
                    "       <div style=\"margin: 32px 0;\">" +
                    "           <span style=\"background: linear-gradient(135deg, #10b981 0%, #0d9488 100%); color: #ffffff; font-size: 32px; font-weight: 700; font-family: monospace; letter-spacing: 8px; padding: 16px 32px; border-radius: 8px; display: inline-block; box-shadow: 0 4px 14px 0 rgba(16, 185, 129, 0.39);\">" + otp + "</span>" +
                    "       </div>" +
                    "       <p style=\"color: #475569; font-size: 15px; line-height: 1.6; text-align: left;\">Mã xác thực này sẽ hết hạn sau <strong>15 phút</strong>. Vui lòng không chia sẻ mã này cho bất kỳ ai để đảm bảo an toàn cho tài khoản của bạn.</p>" +
                    "       <hr style=\"border: none; border-top: 1px solid #e2e8f0; margin: 32px 0;\" />" +
                    "       <p style=\"color: #94a3b8; font-size: 14px; text-align: left; margin-bottom: 0;\">Nếu bạn không yêu cầu khôi phục mật khẩu, xin vui lòng bỏ qua email này.</p>" +
                    "       <p style=\"color: #94a3b8; font-size: 14px; text-align: left; margin-top: 8px;\">Trân trọng,<br><strong style=\"color: #64748b;\">Đội ngũ HESTA</strong></p>" +
                    "   </div>" +
                    "</div>";

            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Password reset OTP email sent successfully to {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}. Error: {}", toEmail, e.getMessage(), e);
            throw new RuntimeException("Không thể gửi email OTP. Vui lòng thử lại sau. Chi tiết: " + e.getMessage());
        }
    }
}
