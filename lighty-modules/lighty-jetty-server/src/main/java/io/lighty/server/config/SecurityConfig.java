/*
 * Copyright (c) 2021 PANTHEON.tech s.r.o. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.server.config;

import java.security.KeyStore;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory.Server;

public class SecurityConfig {
    private final KeyStore keyStore;
    private final KeyStore trustKeyStore;
    private final String ksPassword;
    private final String trustKsPassword;
    private final Server server;
    private final boolean enabledSNI;
    private final boolean isNeedClientAuth;

    public SecurityConfig(final KeyStore keyStore, final String ksPassword, final KeyStore trustKeyStore,
                          final String trustKsPassword, final boolean isNeedClientAuth, final boolean isEnabledSNI) {
        this.keyStore = keyStore;
        this.ksPassword = ksPassword;
        this.trustKeyStore = trustKeyStore;
        this.trustKsPassword = trustKsPassword;
        this.isNeedClientAuth = isNeedClientAuth;
        this.enabledSNI = isEnabledSNI;
        server = new Server();
        initFactoryCtx();
    }

    private void initFactoryCtx() {
        server.setTrustStore(trustKeyStore);
        server.setTrustStorePassword(trustKsPassword);
        server.setKeyStore(keyStore);
        server.setKeyStorePassword(ksPassword);
        server.setCipherComparator(HTTP2Cipher.COMPARATOR);
        server.setNeedClientAuth(isNeedClientAuth);
        server.setSniRequired(enabledSNI);
    }

    public SslConnectionFactory getSslConnectionFactory(final String protocol) {
        return new SslConnectionFactory(server, protocol);
    }

    public boolean isEnabledSNI() {
        return enabledSNI;
    }
}
