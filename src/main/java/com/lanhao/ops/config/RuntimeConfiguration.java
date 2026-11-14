package com.lanhao.ops.config;

import com.lanhao.ops.AgentProperties;
import io.fabric8.kubernetes.client.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class RuntimeConfiguration {
    @Bean
    UserDetailsService users(AgentProperties config) {
        return new InMemoryUserDetailsManager(User.withUsername(config.apiUsername())
                .password("{bcrypt}" + new BCryptPasswordEncoder().encode(config.apiPassword())).roles("OPERATOR").build());
    }
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .addFilterBefore(new com.lanhao.ops.console.ConsoleOriginFilter(),org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class)
                .headers(headers->headers.contentSecurityPolicy(csp->csp.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")))
                .sessionManagement(session -> session.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health","/","/index.html","/app.css","/app.js","/markdown.js","/vendor/marked.umd.js","/vendor/purify.min.js","/favicon.svg").permitAll()
                        .anyRequest().hasRole("OPERATOR"))
                .httpBasic(Customizer.withDefaults()).build();
    }
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "agent.mode", havingValue = "KUBERNETES")
    KubernetesClient kubernetes(com.lanhao.ops.command.KubectlProperties cli,com.lanhao.ops.command.KubectlIdentity pinned) {
        Config identity;
        if(cli.enabled()) {
            identity=pinned.clientConfig();
        }else identity=Config.autoConfigure(null);
        Config config = new ConfigBuilder(identity).withConnectionTimeout(5000)
                .withRequestTimeout(cli.enabled()?2000:10000).withRequestRetryBackoffLimit(0).build();
        return new KubernetesClientBuilder().withConfig(config).build();
    }
}
