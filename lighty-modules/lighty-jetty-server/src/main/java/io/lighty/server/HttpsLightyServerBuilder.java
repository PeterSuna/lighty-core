/*
 * Copyright (c) 2021 PANTHEON.tech s.r.o. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.server;

import io.lighty.server.config.SecurityConfig;
import java.net.InetSocketAddress;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

public class HttpsLightyServerBuilder extends LightyServerBuilder {
    private final SecurityConfig securityConfig;

    public HttpsLightyServerBuilder(final InetSocketAddress inetSocketAddress, final SecurityConfig securityConfig) {
        super(inetSocketAddress);
        this.securityConfig = securityConfig;
    }

    @Override
    public Server build() {
        super.server = new Server();
        final var server = super.build();
        // HTTPS Configuration
        final var httpsConfig = new HttpConfiguration();
        httpsConfig.setSecurePort(inetSocketAddress.getPort());
        httpsConfig.setSendXPoweredBy(true);
        httpsConfig.addCustomizer(new SecureRequestCustomizer(securityConfig.isEnabledSNI()));
        final var httpConnectionFactory = new HttpConnectionFactory(httpsConfig);

        // SSL Connection Factory
        final var ssl = securityConfig.getSslConnectionFactory(HttpVersion.HTTP_1_1.asString());
        final var sslConnector = new ServerConnector(server, ssl, httpConnectionFactory);
        sslConnector.setPort(this.inetSocketAddress.getPort());

        server.addConnector(sslConnector);
        return server;
    }
}
