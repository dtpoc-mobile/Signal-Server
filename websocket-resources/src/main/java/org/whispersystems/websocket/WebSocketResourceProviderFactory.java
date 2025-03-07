/*
 * Copyright 2013 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.websocket;

import static java.util.Optional.ofNullable;

import io.dropwizard.jersey.jackson.JacksonMessageBodyProvider;
import java.io.IOException;
import java.security.Principal;
import java.util.Optional;
import javax.ws.rs.InternalServerErrorException;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.JettyWebSocketCreator;
import org.eclipse.jetty.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.glassfish.jersey.server.ApplicationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.websocket.auth.AuthenticationException;
import org.whispersystems.websocket.auth.WebSocketAuthenticator;
import org.whispersystems.websocket.auth.WebSocketAuthenticator.AuthenticationResult;
import org.whispersystems.websocket.auth.WebsocketAuthValueFactoryProvider;
import org.whispersystems.websocket.configuration.WebSocketConfiguration;
import org.whispersystems.websocket.session.WebSocketSessionContextValueFactoryProvider;
import org.whispersystems.websocket.setup.WebSocketEnvironment;

public class WebSocketResourceProviderFactory<T extends Principal> extends JettyWebSocketServlet implements
    JettyWebSocketCreator {

  private static final Logger logger = LoggerFactory.getLogger(WebSocketResourceProviderFactory.class);

  private final WebSocketEnvironment<T> environment;
  private final ApplicationHandler jerseyApplicationHandler;
  private final WebSocketConfiguration configuration;

  private final String remoteAddressPropertyName;

  public WebSocketResourceProviderFactory(WebSocketEnvironment<T> environment, Class<T> principalClass,
      WebSocketConfiguration configuration, String remoteAddressPropertyName) {
    this.environment = environment;

    environment.jersey().register(new WebSocketSessionContextValueFactoryProvider.Binder());
    environment.jersey().register(new WebsocketAuthValueFactoryProvider.Binder<T>(principalClass));
    environment.jersey().register(new JacksonMessageBodyProvider(environment.getObjectMapper()));

    this.jerseyApplicationHandler = new ApplicationHandler(environment.jersey());

    this.configuration = configuration;
    this.remoteAddressPropertyName = remoteAddressPropertyName;
  }

  @Override
  public Object createWebSocket(final JettyServerUpgradeRequest request, final JettyServerUpgradeResponse response) {
    try {
      Optional<WebSocketAuthenticator<T>> authenticator = Optional.ofNullable(environment.getAuthenticator());
      T authenticated = null;

      if (authenticator.isPresent()) {
        AuthenticationResult<T> authenticationResult = authenticator.get().authenticate(request);

        if (authenticationResult.getUser().isEmpty() && authenticationResult.credentialsPresented()) {
          response.sendForbidden("Unauthorized");
          return null;
        } else {
          authenticated = authenticationResult.getUser().orElse(null);
        }
      }

      return new WebSocketResourceProvider<>(getRemoteAddress(request),
          remoteAddressPropertyName,
          this.jerseyApplicationHandler,
          this.environment.getRequestLog(),
          authenticated,
          this.environment.getMessageFactory(),
          ofNullable(this.environment.getConnectListener()),
          this.environment.getIdleTimeout());
    } catch (AuthenticationException | IOException e) {
      logger.warn("Authentication failure", e);
      try {
        response.sendError(500, "Failure");
      } catch (IOException ignored) {
      }
      return null;
    }
  }

  @Override
  public void configure(JettyWebSocketServletFactory factory) {
    factory.setCreator(this);
    factory.setMaxBinaryMessageSize(configuration.getMaxBinaryMessageSize());
    factory.setMaxTextMessageSize(configuration.getMaxTextMessageSize());
  }

  private String getRemoteAddress(JettyServerUpgradeRequest request) {
    final String remoteAddress = (String) request.getHttpServletRequest().getAttribute(remoteAddressPropertyName);
    if (StringUtils.isBlank(remoteAddress)) {
      logger.error("Remote address property is not present");
      throw new InternalServerErrorException();
    }
    return remoteAddress;
  }
}
