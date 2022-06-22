/*
 * Copyright (c) 2018 PANTHEON.tech s.r.o. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.server.test;

import io.lighty.core.controller.impl.config.ConfigurationException;
import io.lighty.server.Http2LightyServerBuilder;
import io.lighty.server.HttpsLightyServerBuilder;
import io.lighty.server.LightyServerBuilder;
import io.lighty.server.config.LightyServerConfig;
import io.lighty.server.config.SecurityConfig;
import io.lighty.server.util.LightyServerConfigUtils;
import java.net.InetSocketAddress;
import java.util.EventListener;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.FilterHolder;
import org.testng.Assert;
import org.testng.annotations.Test;

public class LightyServerBuilderTest {

    @Test
    public void testServerBuilder() {

        FilterHolder filterHolder = new FilterHolder();
        ContextHandlerCollection contexts = new ContextHandlerCollection();

        LightyServerBuilder serverBuilder = new LightyServerBuilder(new InetSocketAddress(8080));
        serverBuilder.addCommonEventListener(new EventListener(){});
        serverBuilder.addCommonFilter(filterHolder, "/path");
        serverBuilder.addCommonInitParameter("key", "value");
        serverBuilder.addContextHandler(contexts);
        Server server = serverBuilder.build();
        Assert.assertNotNull(server);
    }

    @Test
    public void testHttpsServerBuilder() throws ConfigurationException {

        FilterHolder filterHolder = new FilterHolder();
        ContextHandlerCollection contexts = new ContextHandlerCollection();

        LightyServerConfig lightyServerConfig = LightyServerConfigUtils.getDefaultLightyServerConfig();
        SecurityConfig securityConfig = lightyServerConfig.getSecurityConfig();

        LightyServerBuilder serverBuilder = new HttpsLightyServerBuilder(new InetSocketAddress(8080), securityConfig);
        serverBuilder.addCommonEventListener(new EventListener(){});
        serverBuilder.addCommonFilter(filterHolder, "/path");
        serverBuilder.addCommonInitParameter("key", "value");
        serverBuilder.addContextHandler(contexts);
        Server server = serverBuilder.build();
        Assert.assertNotNull(server);
    }

    @Test
    public void testHttp2ServerBuilder() throws ConfigurationException {

        FilterHolder filterHolder = new FilterHolder();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        LightyServerConfig lightyServerConfig = LightyServerConfigUtils.getDefaultLightyServerConfig();
        SecurityConfig securityConfig = lightyServerConfig.getSecurityConfig();

        LightyServerBuilder serverBuilder = new Http2LightyServerBuilder(new InetSocketAddress(8080), securityConfig);
        serverBuilder.addCommonEventListener(new EventListener(){});
        serverBuilder.addCommonFilter(filterHolder, "/path");
        serverBuilder.addCommonInitParameter("key", "value");
        serverBuilder.addContextHandler(contexts);
        Server server = serverBuilder.build();
        Assert.assertNotNull(server);
    }

}
