package com.ticketing.gateway.security;

import com.ticketing.gateway.repo.EmployeeRepository;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@Service
public class JpaUserDetailsService implements UserDetailsService {

    private final EmployeeRepository employees;

    public JpaUserDetailsService(EmployeeRepository employees) {
        this.employees = employees;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var emp = employees.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        var authorities = emp.getRoles().stream()
                .map(r -> new SimpleGrantedAuthority(r.getName()))
                .toList();

        return User.withUsername(emp.getUsername())
                .password(emp.getPassword())
                .authorities(authorities)
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .disabled(false)
                .build();
    }
}
