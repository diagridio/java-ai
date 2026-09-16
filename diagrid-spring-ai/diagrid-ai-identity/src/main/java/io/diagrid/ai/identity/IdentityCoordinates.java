package io.diagrid.ai.identity;

/**
 * Where tokens come from and how to check them: the three values every verification needs.
 *
 * @param issuer   expected {@code iss} claim
 * @param jwksUri  endpoint publishing the issuer's signing keys
 * @param audience expected {@code aud} claim; empty means the audience is not checked
 */
record IdentityCoordinates(String issuer, String jwksUri, String audience) {
}
