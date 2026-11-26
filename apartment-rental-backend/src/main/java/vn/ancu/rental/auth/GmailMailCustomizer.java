package vn.ancu.rental.auth;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component
public class GmailMailCustomizer implements BeanPostProcessor {
    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof JavaMailSenderImpl sender) {
            sender.setHost(cleanValue(sender.getHost()));
            sender.setUsername(cleanValue(sender.getUsername()));
            if ("smtp.gmail.com".equalsIgnoreCase(sender.getHost()) && sender.getPassword() != null) {
                // Google displays app passwords in four groups of four characters.
                sender.setPassword(cleanValue(sender.getPassword()).replaceAll("\\s+", ""));
            }
        }
        return bean;
    }

    static String cleanValue(String value) {
        if (value == null) return null;
        String clean = value.trim();
        if (clean.length() >= 2 && ((clean.startsWith("\"") && clean.endsWith("\""))
                || (clean.startsWith("'") && clean.endsWith("'")))) {
            return clean.substring(1, clean.length() - 1);
        }
        return clean;
    }
}
