package com.schoolsoft;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@SpringBootApplication
@Modulithic(
    sharedModules = { "platform" },
    systemName = "Schoolsoft"
)
public class SchoolsoftApplication {
    public static void main(String[] args) {
        SpringApplication.run(SchoolsoftApplication.class, args);
    }
}
