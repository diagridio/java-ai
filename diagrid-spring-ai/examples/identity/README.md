# identity example

A two-route Spring Boot service that verifies the end-user token Catalyst puts on every inbound
request, reads the caller in a handler, and carries that same caller onto one outbound call.
Deliberately minimal: no agent, no model, no state store — only identity.

## How it works

The whole install is one `@Bean` in `IdentityApplication` — build the policy, hand it to the
filter. Spring Boot registers any `Filter` bean, so both routes are protected:

```java
@Bean
OAuthFilter diagridOAuthFilter() {
  return new OAuthFilter(new OAuthConfig());
}
```

The default `OAuthConfig` is fail-closed (`requireAuth` is `true`) and names no issuer, audience or
JWKS endpoint, so those are discovered from the sidecar's `/v1.0/metadata` at first use. It requires
no scopes either — scope handling is shown in the handler instead, which keeps the example runnable
without setting any up.

Three things are demonstrated:

- **Inbound verification** — the filter checks the signature and claims of the
  `X-Diagrid-User-Token` header before `IdentityController` runs, and rejects anything that fails
  with a `{"error":"oauth.…"}` body.
- **Reading the caller** — `GET /whoami` gets a `VerifiedUser` from the typed accessor
  `OAuthFilter.verifiedUser(request)`, never a cast out of the servlet's attribute bag, and answers
  with its `subject`, `tenant`, `scopes` and `user.hasScope("read")`.
- **Outbound propagation** — `GET /downstream` makes one HTTP GET to `DOWNSTREAM_URL` (default
  `http://localhost:8081/whoami`) through the SDK's identity-aware client, so the callee verifies
  the same caller. An outbound failure answers `502 {"error":"downstream_unreachable"}` — the
  example's own error string, not an SDK code.

The outbound call costs nothing beyond the client the controller had to construct anyway. It builds
one, once, and then makes an ordinary call — no identity header is assembled anywhere in the
example:

```java
private final HttpClient http =
    IdentityHttpClient.from(HttpClient.newBuilder().connectTimeout(TIMEOUT), HttpClient.Redirect.NORMAL);

HttpResponse<String> response = http.send(outbound, HttpResponse.BodyHandlers.ofString());
```

What comes back from `IdentityHttpClient` is a `java.net.http.HttpClient`, so the same instance goes
wherever one is expected — an MCP client, a generated API client, a Spring
`JdkClientHttpRequestFactory`. The caller is read at send time rather than at construction, which is
what makes one shared client safe when several requests are in flight at once.

## Run it

The example is a standalone app, not a module of the library build, so the identity modules have to
be installed into the local repository first:

```bash
cd ..                       # diagrid-spring-ai/
mvn -B -pl diagrid-ai-identity,diagrid-spring-ai-identity -am install -DskipTests
cd examples/identity
mvn package -DskipTests
```

Then run it on Catalyst, which supplies the identity coordinates the filter discovers and sets the
user-token header on requests it forwards:

```bash
diagrid login
diagrid project create identity-example --wait --use
diagrid dev run -f identity-dev.yaml --approve
```

To see propagation end to end, start a second copy as the downstream and point the first at it:

```bash
DOWNSTREAM_URL=http://localhost:8081/whoami mvn spring-boot:run          # terminal 1
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081       # terminal 2
```

## Try it

With a token (Catalyst's own calls carry one; `$TOKEN` below is a user token from your project):

```bash
curl -s -H "X-Diagrid-User-Token: Bearer $TOKEN" http://localhost:8080/whoami
# -> {"subject":"user@example.com","tenant":"acme","scopes":["read","write"],"hasRead":true}

curl -s -H "X-Diagrid-User-Token: Bearer $TOKEN" http://localhost:8080/downstream
# -> {"downstream":"{\"subject\":\"user@example.com\",\"tenant\":\"acme\",\"scopes\":[\"read\",\"write\"],\"hasRead\":true}"}
```

Every rejection has the same shape — `{"error":"<code>"}`, with a code shared by every Diagrid
SDK, so a caller branches on the code without caring which language served the request:

```bash
curl -s -i http://localhost:8080/whoami
# -> HTTP/1.1 401
# -> {"error":"oauth.missing_token"}

curl -s -i -H "X-Diagrid-User-Token: Bearer not-a-jwt" http://localhost:8080/whoami
# -> HTTP/1.1 401
# -> {"error":"oauth.decode_error"}

curl -s -i -H "X-Diagrid-User-Token: Bearer $EXPIRED_TOKEN" http://localhost:8080/whoami
# -> HTTP/1.1 401
# -> {"error":"oauth.expired"}
```

Away from a sidecar there is nothing to discover an issuer from, so the first request that *carries*
a token answers `503 {"error":"oauth.not_configured"}` instead — the app refuses rather than serving
that request unauthenticated. The tokenless `401` still holds, since it is decided before any
verification.

A downstream that is not up:

```bash
curl -s -i -H "X-Diagrid-User-Token: Bearer $TOKEN" http://localhost:8080/downstream
# -> HTTP/1.1 502
# -> {"error":"downstream_unreachable"}
```

## Notes

- The token comes from the `X-Diagrid-User-Token` header, which the Catalyst sidecar sets on the
  requests it forwards to your app. Only that header is honoured: `Authorization` belongs to
  whatever the app itself authenticates with, and treating it as a user token would let a caller's
  own credential impersonate an end user.
- `requireAuth` stays `true` here. Set it to `false` when unauthenticated routes — health,
  readiness — share the app; the filter then lets a tokenless request through with no verified
  caller attached, and `OAuthFilter.verifiedUser(request)` answers empty so the handler decides.
  It governs the tokenless case and nothing else: a token that *is* present is always verified
  either way, and an invalid one is always refused.
- The outbound client carries the caller's credential only to the origin the handler addressed. If
  the downstream answers with a redirect to a different host, the hop is followed with no identity
  header — otherwise a callee could name any host and be handed the caller's on-behalf-of
  credential.
- A request that arrives with no caller — a cron trigger, a pub/sub delivery — is not an error on
  the way out either: the outbound call goes ahead with no identity header at all (not an empty
  one), and the omission is logged at debug.
- `allowInsecureJwks` lets the JWKS endpoint be plain `http` on a non-loopback host. It is not set
  in this example and must not be set in production: the published key set is the whole root of
  trust, so anyone who can rewrite a plaintext response can mint tokens the filter accepts. Local
  development against a loopback sidecar needs no opt-in at all.
