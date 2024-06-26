/*
 * Copyright (C) 2024 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.projectnessie.client.auth.oauth2;

import static org.projectnessie.client.auth.oauth2.OAuth2ClientUtils.tokenExpirationTime;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.projectnessie.client.http.HttpRequest;
import org.projectnessie.client.http.HttpResponse;

/**
 * Infrastructure shared by all flows.
 *
 * <p>The general behavior adopted by the OAuth2 client wrt to client authentication is as follows:
 *
 * <p>For confidential clients:
 *
 * <ol>
 *   <li>Authenticate the client with a Basic authentication header (while it is also possible to
 *       authenticate using {@code client_id} + {@code client_secret} in the request body, the OAuth
 *       2.0 spec considers this method less secure, so we don't use it);
 *   <li>Do NOT include {@code client_id} nor {@code client_secret} in the request body, since the
 *       spec also forbids more than one authentication method in each request.
 * </ol>
 *
 * <p>For public clients:
 *
 * <ol>
 *   <li>Do not include any Basic authentication header (since there is no client secret);
 *   <li>But do include {@code client_id} in the request body, in order to identify the client
 *       (according to the spec, including {@code client_id} is mandatory for public clients using
 *       the Authorization Code Grant, and optional for other grants – but we always include it).
 * </ol>
 */
abstract class AbstractFlow implements Flow {

  final OAuth2ClientConfig config;

  AbstractFlow(OAuth2ClientConfig config) {
    this.config = config;
  }

  <REQ extends TokensRequestBase, RESP extends TokensResponseBase> Tokens invokeTokenEndpoint(
      TokensRequestBase.Builder<REQ> request, Class<? extends RESP> responseClass) {
    config.getScope().ifPresent(request::scope);
    maybeAddClientId(request);
    return invokeEndpoint(config.getResolvedTokenEndpoint(), request.build(), responseClass)
        .asTokens(config.getClock());
  }

  DeviceCodeResponse invokeDeviceAuthEndpoint() {
    DeviceCodeRequest.Builder request = DeviceCodeRequest.builder();
    config.getScope().ifPresent(request::scope);
    maybeAddClientId(request);
    return invokeEndpoint(
        config.getResolvedDeviceAuthEndpoint(), request.build(), DeviceCodeResponse.class);
  }

  private void maybeAddClientId(Object request) {
    if (config.isPublicClient() && request instanceof PublicClientRequest.Builder) {
      ((PublicClientRequest.Builder<?>) request).clientId(config.getClientId());
    }
  }

  private <REQ, RESP> RESP invokeEndpoint(
      URI endpoint, REQ request, Class<? extends RESP> responseClass) {
    HttpRequest req = config.getHttpClient().newRequest(endpoint);
    config.getBasicAuthentication().ifPresent(req::authentication);
    HttpResponse response = req.postForm(request);
    return response.readEntity(responseClass);
  }

  protected boolean isAboutToExpire(Token token, Duration defaultLifespan) {
    Instant now = config.getClock().get();
    Instant expirationTime = tokenExpirationTime(now, token, defaultLifespan);
    return expirationTime.isBefore(now.plus(config.getRefreshSafetyWindow()));
  }
}
