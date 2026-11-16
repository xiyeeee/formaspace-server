package com.xiye.formaspace.auth.service;

import com.xiye.formaspace.config.EmailCodeProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class VerificationMailSender {
    private final ObjectProvider<JavaMailSender> senders;
    private final EmailCodeProperties properties;
    private final String template;

    public VerificationMailSender(ObjectProvider<JavaMailSender> senders, EmailCodeProperties properties,
            Environment environment) throws IOException {
        this.senders = senders;
        this.properties = properties;
        template = new ClassPathResource("templates/mail/register-code.txt").getContentAsString(StandardCharsets.UTF_8);
        if (!properties.isEnabled()) {
            return;
        }
        for (String name : new String[] {"spring.mail.host", "spring.mail.username", "spring.mail.password"}) {
            if (environment.getProperty(name, "").isBlank()) {
                throw new IllegalStateException("启用邮件功能时必须设置 " + name);
            }
        }
        if (properties.getFrom() == null || properties.getFrom().isBlank()
                || properties.getFrom().contains("\r") || properties.getFrom().contains("\n")) {
            throw new IllegalStateException("启用邮件功能时必须设置合法 SMTP_FROM");
        }
        boolean ssl = environment.getProperty("spring.mail.properties[mail.smtp.ssl.enable]", Boolean.class, false);
        boolean starttls = environment.getProperty("spring.mail.properties[mail.smtp.starttls.enable]", Boolean.class, true);
        if (ssl == starttls) {
            throw new IllegalStateException("邮件 SSL 和 STARTTLS 必须且只能启用一种");
        }
        try {
            new jakarta.mail.internet.InternetAddress(properties.getFrom(), true).validate();
        } catch (jakarta.mail.internet.AddressException exception) {
            throw new IllegalStateException("SMTP_FROM 格式不正确");
        }
    }

    public void sendRegistration(String email, String code, Duration ttl) {
        var sender = senders.getIfAvailable();
        if (!properties.isEnabled() || sender == null) {
            throw new MailSendException("Mail unavailable");
        }
        var message = new SimpleMailMessage();
        message.setFrom(properties.getFrom());
        message.setTo(email);
        message.setSubject("FormaSpace · 注册验证码");
        message.setText(template.replace("{{code}}", code).replace("{{seconds}}", Long.toString(ttl.toSeconds())));
        sender.send(message);
    }
}
