package io.diagrid.springai.examples.identity;

import io.diagrid.ai.identity.OAuthConfig;
import io.diagrid.springai.identity.OAuthFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Inbound identity in one bean: build the policy, hand it to the filter. Spring Boot registers any
 * {@code Filter} bean, so every route below it is protected from here on.
 *
 * <p>The default {@link OAuthConfig} is fail-closed — a request with no user token is rejected with
 * {@code 401 {"error":"oauth.missing_token"}} — and it names no issuer, audience or JWKS endpoint,
 * so those are discovered from the Catalyst sidecar at first use. No scopes are required here; the
 * per-route scope check lives in the handler instead (see {@code IdentityController}).
 */
@SpringBootApplication
public class IdentityApplication {

  public static void main(String[] args) {
    SpringApplication.run(IdentityApplication.class, args);
  }

  @Bean
  OAuthFilter diagridOAuthFilter() {
    return new OAuthFilter(new OAuthConfig());
  }
}
