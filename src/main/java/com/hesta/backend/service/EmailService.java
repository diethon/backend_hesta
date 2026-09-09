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

    /**
     * Core email sending method. Returns true if sent, false if failed.
     */
    private boolean trySendHtmlEmail(String to, String subject, String htmlContent) {
        try {
            log.info("Attempting to send email to: {} | Subject: {}", to, subject);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            mailSender.send(message);
            log.info("✅ Email sent successfully to: {}", to);
            return true;
        } catch (Exception e) {
            log.error("❌ Failed to send email to {}. Error: {}", to, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Send password reset OTP. Throws RuntimeException on failure since OTP delivery is critical.
     */
    public void sendPasswordResetOtp(String to, String otp) {
        String subject = "Mã xác nhận khôi phục mật khẩu - HESTA Smart Home";
        String htmlContent = String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #f4f4f5; margin: 0; padding: 20px; }
                    .container { max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 12px; padding: 40px; box-shadow: 0 4px 6px rgba(0,0,0,0.05); }
                    .header { text-align: center; margin-bottom: 30px; }
                    .logo { color: #0891b2; font-size: 28px; font-weight: bold; text-decoration: none; }
                    .title { color: #1e293b; font-size: 24px; margin-top: 10px; margin-bottom: 20px; }
                    .otp-box { background-color: #f8fafc; border: 2px dashed #cbd5e1; border-radius: 8px; padding: 20px; text-align: center; margin: 30px 0; }
                    .otp-code { font-size: 32px; font-weight: bold; color: #0891b2; letter-spacing: 5px; }
                    .content { color: #475569; line-height: 1.6; font-size: 16px; }
                    .footer { margin-top: 40px; padding-top: 20px; border-top: 1px solid #e2e8f0; text-align: center; color: #94a3b8; font-size: 14px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <span class="logo">HESTA</span>
                    </div>
                    <div class="content">
                        <h1 class="title">Xin chào,</h1>
                        <p>Chúng tôi nhận được yêu cầu khôi phục mật khẩu cho tài khoản của bạn tại HESTA Smart Home.</p>
                        <p>Vui lòng sử dụng mã xác nhận dưới đây để tiến hành đặt lại mật khẩu:</p>
                        
                        <div class="otp-box">
                            <div class="otp-code">%s</div>
                        </div>
                        
                        <p><strong>Lưu ý:</strong> Mã này sẽ hết hạn sau 15 phút. Vì lý do bảo mật, vui lòng không chia sẻ mã này với bất kỳ ai.</p>
                        <p>Nếu bạn không yêu cầu khôi phục mật khẩu, xin vui lòng bỏ qua email này hoặc liên hệ với bộ phận hỗ trợ của chúng tôi.</p>
                        
                        <p style="margin-top: 30px;">Trân trọng,<br><strong>Đội ngũ HESTA Smart Home</strong></p>
                    </div>
                    <div class="footer">
                        Email này được gửi tự động từ hệ thống HESTA. Vui lòng không trả lời trực tiếp.
                    </div>
                </div>
            </body>
            </html>
            """, otp);

        boolean sent = trySendHtmlEmail(to, subject, htmlContent);
        if (!sent) {
            throw new RuntimeException("Không thể gửi email OTP. Vui lòng thử lại sau.");
        }
    }

    /**
     * Send invitation email. Returns true if sent successfully, false if failed.
     * Does NOT throw — caller decides how to handle failure.
     */
    public boolean sendInvitationEmail(String to, String inviterName, String homeName, String inviteCode, String inviteToken) {
        String subject = "Lời mời tham gia nhà thông minh - HESTA";
        String link = "http://localhost:5173/join?token=" + inviteToken;

        log.info("Preparing invitation email: to={}, inviter={}, home={}, token={}", to, inviterName, homeName, inviteToken);
        
        String htmlContent = buildInvitationHtml(inviterName, homeName, link, inviteCode);

        return trySendHtmlEmail(to, subject, htmlContent);
    }

    private String buildInvitationHtml(String inviterName, String homeName, String link, String inviteCode) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><style>");
        sb.append("body{font-family:'Segoe UI',Tahoma,Geneva,Verdana,sans-serif;background-color:#f4f4f5;margin:0;padding:20px;}");
        sb.append(".container{max-width:600px;margin:0 auto;background-color:#ffffff;border-radius:12px;padding:40px;box-shadow:0 4px 6px rgba(0,0,0,0.05);}");
        sb.append(".header{text-align:center;margin-bottom:30px;}");
        sb.append(".logo{color:#f59e0b;font-size:28px;font-weight:bold;}");
        sb.append(".title{color:#1e293b;font-size:24px;margin-top:10px;margin-bottom:20px;}");
        sb.append(".otp-box{background-color:#f8fafc;border:2px dashed #cbd5e1;border-radius:8px;padding:20px;text-align:center;margin:20px 0;}");
        sb.append(".otp-code{font-size:28px;font-weight:bold;color:#f59e0b;letter-spacing:5px;}");
        sb.append(".content{color:#475569;line-height:1.6;font-size:16px;}");
        sb.append(".btn{display:inline-block;padding:12px 24px;background-color:#f59e0b;color:white;text-decoration:none;border-radius:8px;font-weight:bold;margin:10px 0 20px;}");
        sb.append(".footer{margin-top:40px;padding-top:20px;border-top:1px solid #e2e8f0;text-align:center;color:#94a3b8;font-size:14px;}");
        sb.append("</style></head><body>");
        sb.append("<div class=\"container\">");
        sb.append("<div class=\"header\"><span class=\"logo\">HESTA</span></div>");
        sb.append("<div class=\"content\">");
        sb.append("<h1 class=\"title\">Xin chào,</h1>");
        sb.append("<p>Bạn đã nhận được lời mời từ <strong>").append(escapeHtml(inviterName)).append("</strong>");
        sb.append(" để tham gia quản lý ngôi nhà <strong>").append(escapeHtml(homeName)).append("</strong>");
        sb.append(" trên hệ thống HESTA Smart Home.</p>");
        sb.append("<p>Nhấn vào nút bên dưới để tham gia ngay:</p>");
        sb.append("<div style=\"text-align:center;\">");
        sb.append("<a href=\"").append(link).append("\" class=\"btn\">Tham Gia Ngay</a>");
        sb.append("</div>");
        sb.append("<p>Hoặc, nếu bạn đã có tài khoản, hãy đăng nhập và nhập mã mời sau:</p>");
        sb.append("<div class=\"otp-box\"><div class=\"otp-code\">").append(inviteCode).append("</div></div>");
        sb.append("<p><strong>Lưu ý:</strong> Lời mời này sẽ hết hạn sau 7 ngày.</p>");
        sb.append("<p style=\"margin-top:30px;\">Trân trọng,<br><strong>Đội ngũ HESTA Smart Home</strong></p>");
        sb.append("</div>");
        sb.append("<div class=\"footer\">Email này được gửi tự động từ hệ thống HESTA. Vui lòng không trả lời trực tiếp.</div>");
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
