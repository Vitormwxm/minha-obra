package br.com.minhaobra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
public class MinhaObraApplication {

    public static void main(String[] args) {
        if (br.com.minhaobra.service.BootstrapAdminCli.requested(args)) {
            System.exit(br.com.minhaobra.service.BootstrapAdminCli.run(args));
            return;
        }
        SpringApplication.run(MinhaObraApplication.class, args);
    }
}
