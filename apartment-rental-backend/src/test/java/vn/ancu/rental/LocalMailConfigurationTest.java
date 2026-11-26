package vn.ancu.rental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import vn.ancu.rental.auth.OtpMailService;
import vn.ancu.rental.auth.GmailMailCustomizer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LocalMailConfigurationTest {
    @TempDir Path temporaryDirectory;

    @Test void localPropertiesConfigureGmailAndEnableOtpWithoutSendingMail() throws Exception {
        Path config = temporaryDirectory.resolve("smtp.local.properties");
        Files.writeString(config, """
                spring.mail.host=smtp.gmail.com
                spring.mail.port=587
                spring.mail.username=configuration-test@gmail.com
                spring.mail.password=abcdefghijklmnop
                ancu.mail.from=configuration-test@gmail.com
                spring.mail.properties.mail.smtp.auth=true
                spring.mail.properties.mail.smtp.starttls.enable=true
                spring.mail.properties.mail.smtp.starttls.required=true
                spring.mail.properties.mail.smtp.ssl.enable=false
                """);
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.import=optional:" + config.toUri())
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(OtpMailService.class, GmailMailCustomizer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JavaMailSender.class);
                    JavaMailSenderImpl mail = (JavaMailSenderImpl) context.getBean(JavaMailSender.class);
                    assertThat(mail.getHost()).isEqualTo("smtp.gmail.com");
                    assertThat(mail.getPort()).isEqualTo(587);
                    assertThat(mail.getUsername()).isEqualTo("configuration-test@gmail.com");
                    assertThat(mail.getPassword()).hasSize(16);
                    assertThat(mail.getJavaMailProperties().getProperty("mail.smtp.starttls.required")).isEqualTo("true");
                    assertThatCode(() -> context.getBean(OtpMailService.class).requireConfigured()).doesNotThrowAnyException();
                });
    }

    @Test void dotenvLoadsQuotedGmailValuesAndGroupedAppPassword() throws Exception {
        Path config = temporaryDirectory.resolve(".env");
        Files.writeString(config, """
                SMTP_HOST="smtp.gmail.com"
                SMTP_PORT=587
                SMTP_USERNAME="configuration-test@gmail.com"
                SMTP_PASSWORD="abcd efgh ijkl mnop"
                """);
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.config.import=optional:" + config.toUri() + "[.properties]",
                        "spring.mail.host=${SMTP_HOST:}", "spring.mail.port=${SMTP_PORT:587}",
                        "spring.mail.username=${SMTP_USERNAME:}", "spring.mail.password=${SMTP_PASSWORD:}",
                        "ancu.mail.from=${SMTP_FROM:${SMTP_USERNAME:}}")
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(OtpMailService.class, GmailMailCustomizer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JavaMailSender.class);
                    JavaMailSenderImpl mail = (JavaMailSenderImpl) context.getBean(JavaMailSender.class);
                    assertThat(mail.getHost()).isEqualTo("smtp.gmail.com");
                    assertThat(mail.getUsername()).isEqualTo("configuration-test@gmail.com");
                    assertThat(mail.getPassword()).isEqualTo("abcdefghijklmnop");
                    assertThatCode(() -> context.getBean(OtpMailService.class).requireConfigured()).doesNotThrowAnyException();
                });
    }
}
