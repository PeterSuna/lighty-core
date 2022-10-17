/*
 * Copyright (c) 2018 PANTHEON.tech s.r.o. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.modules.northbound.restconf.community.impl;

import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import io.lighty.core.controller.api.AbstractLightyModule;
import io.lighty.modules.northbound.restconf.community.impl.root.resource.discovery.RootFoundApplication;
import io.lighty.modules.northbound.restconf.community.impl.util.RestConfConfigUtils;
import io.lighty.server.LightyServerBuilder;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import javax.servlet.ServletException;
import org.apache.shiro.config.Ini;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.web.env.DefaultWebEnvironment;
import org.apache.shiro.web.env.IniWebEnvironment;
import org.apache.shiro.web.env.WebEnvironment;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.opendaylight.aaa.filterchain.configuration.impl.CustomFilterAdapterConfigurationImpl;
import org.opendaylight.aaa.shiro.web.env.ShiroWebContextSecurer;
import org.opendaylight.aaa.web.jetty.JettyWebServer;
import org.opendaylight.aaa.web.servlet.jersey2.JerseyServletSupport;
import org.opendaylight.controller.config.threadpool.util.NamingThreadPoolFactory;
import org.opendaylight.controller.config.threadpool.util.ScheduledThreadPoolWrapper;
import org.opendaylight.mdsal.dom.api.DOMActionService;
import org.opendaylight.mdsal.dom.api.DOMDataBroker;
import org.opendaylight.mdsal.dom.api.DOMMountPointService;
import org.opendaylight.mdsal.dom.api.DOMNotificationService;
import org.opendaylight.mdsal.dom.api.DOMRpcService;
import org.opendaylight.mdsal.dom.api.DOMSchemaService;
import org.opendaylight.restconf.nb.rfc8040.DataStreamApplication;
import org.opendaylight.restconf.nb.rfc8040.RestconfApplication;
import org.opendaylight.restconf.nb.rfc8040.handlers.SchemaContextHandler;
import org.opendaylight.restconf.nb.rfc8040.rests.services.impl.RestconfDataStreamServiceImpl;
import org.opendaylight.restconf.nb.rfc8040.streams.Configuration;
import org.opendaylight.restconf.nb.rfc8040.streams.WebSocketInitializer;
import org.opendaylight.restconf.nb.rfc8040.web.WebInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommunityRestConf extends AbstractLightyModule {

    private static final Logger LOG = LoggerFactory.getLogger(CommunityRestConf.class);

    private final DOMDataBroker domDataBroker;
    private final DOMRpcService domRpcService;
    private final DOMNotificationService domNotificationService;
    private final DOMMountPointService domMountPointService;
    private final DOMActionService domActionService;
    private final DOMSchemaService domSchemaService;
    private final int httpPort;
    private final InetAddress inetAddress;
    private final String restconfServletContextPath;
    private Server jettyServer;
    private LightyServerBuilder lightyServerBuilder;
    private SchemaContextHandler schemaCtxHandler;
    private WebInitializer webInitializer;

    public CommunityRestConf(final DOMDataBroker domDataBroker, final DOMRpcService domRpcService,
            final DOMActionService domActionService, final DOMNotificationService domNotificationService,
            final DOMMountPointService domMountPointService,
            final DOMSchemaService domSchemaService, final InetAddress inetAddress,
            final int httpPort, final String restconfServletContextPath,
            final LightyServerBuilder serverBuilder) {
        this.domDataBroker = domDataBroker;
        this.domRpcService = domRpcService;
        this.domActionService = domActionService;
        this.domNotificationService = domNotificationService;
        this.domMountPointService = domMountPointService;
        this.lightyServerBuilder = serverBuilder;
        this.domSchemaService = domSchemaService;
        this.httpPort = httpPort;
        this.inetAddress = inetAddress;
        this.restconfServletContextPath = restconfServletContextPath;
    }

    public CommunityRestConf(final DOMDataBroker domDataBroker,
            final DOMRpcService domRpcService, final DOMActionService domActionService,
            final DOMNotificationService domNotificationService, final DOMMountPointService domMountPointService,
            final DOMSchemaService domSchemaService, final InetAddress inetAddress, final int httpPort,
            final String restconfServletContextPath) {
        this(domDataBroker, domRpcService, domActionService, domNotificationService,
                domMountPointService, domSchemaService, inetAddress, httpPort,
                restconfServletContextPath, null);
    }

    @Override
    protected boolean initProcedure() {
        final Stopwatch stopwatch = Stopwatch.createStarted();
        final Configuration streamsConfiguration = RestConfConfigUtils.getStreamsConfiguration();

        final NamingThreadPoolFactory threadPoolFactory = new NamingThreadPoolFactory("ping-executor");
        final ScheduledThreadPoolWrapper threadPoolWrapper = new ScheduledThreadPoolWrapper(1, threadPoolFactory);
        RestconfDataStreamServiceImpl streamService = new RestconfDataStreamServiceImpl(threadPoolWrapper,
                streamsConfiguration);

        this.schemaCtxHandler
                = new SchemaContextHandler(this.domDataBroker, this.domSchemaService);
        this.schemaCtxHandler.init();

        final DataStreamApplication dataStreamApplication = new DataStreamApplication(schemaCtxHandler, domMountPointService,
                streamService);
        final WebSocketInitializer webSocketInitializer = new WebSocketInitializer(threadPoolWrapper, streamsConfiguration);
        final JettyWebServer jettyWebServer = new JettyWebServer(8558);

        DefaultWebEnvironment defaultWebEnvironment = new DefaultWebEnvironment();
        defaultWebEnvironment.setWebSecurityManager(new DefaultWebSecurityManager());
        final ShiroWebContextSecurer webContextSecurer = new ShiroWebContextSecurer(defaultWebEnvironment);

        LOG.info("Starting RestconfApplication with configuration {}", streamsConfiguration);

        final RestconfApplication restconfApplication = new RestconfApplication(schemaCtxHandler,
                this.domMountPointService, this.domDataBroker, this.domRpcService, this.domActionService,
                this.domNotificationService, this.domSchemaService, streamsConfiguration);
        final ServletContainer servletContainer8040 = new ServletContainer(ResourceConfig
                .forApplication(restconfApplication));
        final ServletHolder jaxrs = new ServletHolder(servletContainer8040);

        LOG.info("RestConf init complete, starting Jetty");
        LOG.info("http address:port {}:{}, url prefix: {}", this.inetAddress.toString(), this.httpPort,
                this.restconfServletContextPath);

        try {
            webInitializer = new WebInitializer(jettyWebServer, webContextSecurer,
                    new JerseyServletSupport(), restconfApplication,
                    dataStreamApplication, new CustomFilterAdapterConfigurationImpl(), webSocketInitializer);
        } catch (ServletException e) {
            LOG.error("Failed to start WebInitializer: ", e);
            return false;
        }
        try {
            final InetSocketAddress inetSocketAddress = new InetSocketAddress(this.inetAddress, this.httpPort);
            final ContextHandlerCollection contexts = new ContextHandlerCollection();
            final ServletContextHandler mainHandler =
                    new ServletContextHandler(contexts, this.restconfServletContextPath, true, false);
            mainHandler.addServlet(jaxrs, "/*");

            final ServletContextHandler rrdHandler =
                    new ServletContextHandler(contexts, "/.well-known", true, false);
            final RootFoundApplication rootDiscoveryApp = new RootFoundApplication(restconfServletContextPath);
            rrdHandler.addServlet(new ServletHolder(new ServletContainer(ResourceConfig
                    .forApplication(rootDiscoveryApp))), "/*");

            boolean startDefault = false;
            if (this.lightyServerBuilder == null) {
                this.lightyServerBuilder = new LightyServerBuilder(inetSocketAddress);
                startDefault = true;
            }
            this.lightyServerBuilder.addContextHandler(contexts);
            if (startDefault) {
                startServer();
            }
        } catch (final IllegalStateException e) {
            LOG.error("Failed to start jetty: ", e);
        }
        LOG.info("Lighty RestConf started in {}", stopwatch.stop());
        return true;
    }

    @SuppressWarnings("checkstyle:illegalCatch")
    @Override
    protected boolean stopProcedure() {
        boolean stopFailed = false;
        if (webInitializer != null) {
            webInitializer.close();
        }
        if (this.schemaCtxHandler != null) {
            this.schemaCtxHandler.close();
            LOG.info("Schema context handler closed");
        }
        if (this.jettyServer != null) {
            try {
                this.jettyServer.stop();
                LOG.info("Jetty stopped");
            } catch (final Exception e) {
                LOG.error("{} failed to stop!", this.jettyServer.getClass(), e);
                stopFailed = true;
            }
        }
        if (this.lightyServerBuilder != null) {
            this.lightyServerBuilder = null;
        }
        return !stopFailed;
    }

    @SuppressWarnings("checkstyle:illegalCatch")
    public void startServer() {
        if (this.jettyServer != null && !this.jettyServer.isStopped()) {
            return;
        }
        try {
            this.jettyServer = this.lightyServerBuilder.build();
            this.jettyServer.start();
        } catch (final Exception e) {
            Throwables.throwIfUnchecked(e);
            throw new IllegalStateException("Failed to start jetty!", e);
        }
        LOG.info("Jetty started");
    }

}
