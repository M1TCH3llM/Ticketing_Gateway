package com.ticketing.gateway.config;

import com.ticketing.gateway.domain.Employee;
import com.ticketing.gateway.domain.Role;
import com.ticketing.gateway.repo.EmployeeRepository;
import com.ticketing.gateway.repo.RoleRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataSeeder {

    @Bean
    CommandLineRunner seed(RoleRepository roles, EmployeeRepository employees, PasswordEncoder encoder) {
        return args -> {
            var rUser = roles.findByName("ROLE_USER")
                    .orElseGet(() -> roles.save(Role.builder().name("ROLE_USER").build()));
            var rManager = roles.findByName("ROLE_MANAGER")
                    .orElseGet(() -> roles.save(Role.builder().name("ROLE_MANAGER").build()));
            var rAdmin = roles.findByName("ROLE_ADMIN")
                    .orElseGet(() -> roles.save(Role.builder().name("ROLE_ADMIN").build()));

            if (employees.findByUsername("user").isEmpty()) {
                var e = Employee.builder()
                        .username("user")
                        .password(encoder.encode("user"))
                        .fullName("User One")
                        .build();
                e.getRoles().add(rUser);
                employees.save(e);
            }
            if (employees.findByUsername("manager").isEmpty()) {
                var e = Employee.builder()
                        .username("manager")
                        .password(encoder.encode("manager"))
                        .fullName("Manager One")
                        .build();
                e.getRoles().add(rManager);
                employees.save(e);
            }
            if (employees.findByUsername("admin").isEmpty()) {
                var e = Employee.builder()
                        .username("admin")
                        .password(encoder.encode("admin"))
                        .fullName("Admin One")
                        .build();
                e.getRoles().add(rAdmin);
                employees.save(e);
            }
        };
    }
}
