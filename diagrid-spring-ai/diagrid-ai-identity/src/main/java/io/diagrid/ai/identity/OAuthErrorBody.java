package io.diagrid.ai.identity;

import com.nimbusds.jose.util.JSONObjectUtils;
import java.util.Map;
import java.util.Objects;

/**
 * The response body an identity rejection carries: one field, named {@code error}.
 *
 * <p>Part of the cross-SDK contract rather than a detail of one adapter: a client branches on the
 * code inside this shape, and every framework adapter writes the same bytes without assembling JSON
 * of its own.
 *
 * <p>Serialised rather than concatenated, so a code carrying a character that needs escaping cannot
 * produce a body no parser accepts.
 *
 * @param code the {@link OAuthErrorCodes} value describing the rejection
 */
public record OAuthErrorBody(String code) {

  /** The single member of the body. Its spelling is the wire contract; never rename it here alone. */
  private static final String ERROR_FIELD = "error";

  /**
   * Checks that a code is present, since a body naming no error explains nothing.
   *
   * @throws NullPointerException when {@code code} is {@code null}
   */
  public OAuthErrorBody {
    Objects.requireNonNull(code, "code");
  }

  /**
   * The body as the JSON an adapter writes to the response.
   *
   * @return {@code {"error":"<code>"}}
   */
  public String toJson() {
    return JSONObjectUtils.toJSONString(Map.of(ERROR_FIELD, code));
  }
}
