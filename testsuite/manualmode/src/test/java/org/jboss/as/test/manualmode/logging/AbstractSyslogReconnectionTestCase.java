/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.test.manualmode.logging;

import org.apache.http.HttpStatus;
import org.jboss.as.controller.PathAddress;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;
import org.jboss.as.controller.operations.common.Util;
import static org.jboss.as.test.manualmode.logging.AbstractLoggingTestCase.executeOperation;
import static org.jboss.as.test.manualmode.logging.AbstractLoggingTestCase.getResponse;
import org.jboss.as.test.shared.TimeoutUtil;
import org.jboss.as.test.syslogserver.BlockedAllProtocolsSyslogServerEventHandler;
import org.jboss.as.test.syslogserver.TCPSyslogServerConfig;
import org.jboss.as.test.syslogserver.UDPSyslogServerConfig;
import org.jboss.dmr.ModelNode;
import org.junit.Assert;
import org.productivity.java.syslog4j.SyslogConstants;
import org.productivity.java.syslog4j.server.SyslogServer;
import org.productivity.java.syslog4j.server.SyslogServerConfigIF;
import org.productivity.java.syslog4j.server.SyslogServerIF;

/**
 * @author olukas
 */
public abstract class AbstractSyslogReconnectionTestCase extends AbstractLoggingTestCase {

    protected static final int SYSLOG_UDP_PORT = 10514;
    protected static final int SYSLOG_TCP_PORT = 10515;

    protected static final String SYSLOG_UDP_HANDLER_NAME = "SYSLOG_UDP_HANDLER";
    protected static final String SYSLOG_TCP_HANDLER_NAME = "SYSLOG_TCP_HANDLER";

    protected static final int ADJUSTED_SECOND = TimeoutUtil.adjust(1000);

    protected static SyslogServerIF udpServer;
    protected static SyslogServerIF tcpServer;

    protected String host;

    protected void setupServer() throws Exception {
        //setup application server for TCP syslog
        PathAddress tcpSyslogAddress = PathAddress.pathAddress()
                .append(SUBSYSTEM, "logging")
                .append("custom-handler", SYSLOG_TCP_HANDLER_NAME);
        ModelNode tcpSyslogHandler = Util.createAddOperation(tcpSyslogAddress);
        tcpSyslogHandler.get("class").set("org.jboss.logmanager.handlers.SyslogHandler");
        tcpSyslogHandler.get("module").set("org.jboss.logmanager");
        tcpSyslogHandler.get("formatter").set("%-5p [%c] (%t) %s%E%n");
        tcpSyslogHandler.get("encoding").set("ISO-8859-1");
        tcpSyslogHandler.get("enabled").set("true");
        ModelNode tcpSyslogProperties = tcpSyslogHandler.get("properties");
        tcpSyslogProperties.get("appName").set("Wildfly");
        tcpSyslogProperties.get("facility").set("LOCAL_USE_5");
        tcpSyslogProperties.get("serverHostname").set(host);
        tcpSyslogProperties.get("hostname").set("-");
        tcpSyslogProperties.get("port").set(SYSLOG_TCP_PORT);
        tcpSyslogProperties.get("syslogType").set("RFC5424");
        tcpSyslogProperties.get("protocol").set("TCP");
        tcpSyslogProperties.get("messageDelimiter").set("-");
        tcpSyslogProperties.get("useMessageDelimiter").set("true");
        executeOperation(tcpSyslogHandler);

        //setup application server for UDP syslog
        PathAddress udpSyslogAddress = PathAddress.pathAddress()
                .append(SUBSYSTEM, "logging")
                .append("syslog-handler", SYSLOG_UDP_HANDLER_NAME);
        ModelNode udpSyslogHandler = Util.createAddOperation(udpSyslogAddress);
        udpSyslogHandler.get("enabled").set("true");
        udpSyslogHandler.get("app-name").set("Wildfly");
        udpSyslogHandler.get("facility").set("LOCAL_USE_5");
        udpSyslogHandler.get("server-address").set(host);
        udpSyslogHandler.get("hostname").set("-");
        udpSyslogHandler.get("port").set(SYSLOG_UDP_PORT);
        udpSyslogHandler.get("syslog-format").set("RFC5424");
        executeOperation(udpSyslogHandler);

        // add logging category
        PathAddress categoryLoggerAddress = PathAddress.pathAddress()
                .append(SUBSYSTEM, "logging")
                .append("logger", LoggingServiceActivator.class.getPackage().getName());
        ModelNode registerTcpSyslogHandler = Util.createAddOperation(categoryLoggerAddress);
        registerTcpSyslogHandler.get("level").set("INFO");
        registerTcpSyslogHandler.get("handlers").add(SYSLOG_TCP_HANDLER_NAME);
        registerTcpSyslogHandler.get("handlers").add(SYSLOG_UDP_HANDLER_NAME);
        executeOperation(registerTcpSyslogHandler);
    }

    protected void tearDownServer() throws Exception {
        PathAddress categoryLoggerAddress = PathAddress.pathAddress()
                .append(SUBSYSTEM, "logging")
                .append("logger", LoggingServiceActivator.class.getPackage().getName());
        executeOperation(Util.createRemoveOperation(categoryLoggerAddress));

        PathAddress udpAddress = PathAddress.pathAddress()
                .append(SUBSYSTEM, "logging")
                .append("syslog-handler", SYSLOG_UDP_HANDLER_NAME);
        executeOperation(Util.createRemoveOperation(udpAddress));

        PathAddress tcpAddress = PathAddress.pathAddress()
                .append(SUBSYSTEM, "logging")
                .append("custom-handler", SYSLOG_TCP_HANDLER_NAME);
        executeOperation(Util.createRemoveOperation(tcpAddress));
    }

    private static SyslogServerIF createAndStartSyslogInstance(SyslogServerConfigIF config, String host, int port, String protocol) {
        config.setUseStructuredData(true);
        config.setHost(host);
        config.setPort(port);
        config.addEventHandler(new BlockedAllProtocolsSyslogServerEventHandler(protocol));
        SyslogServerIF syslogServer = SyslogServer.createInstance(protocol, config);
        SyslogServer.getThreadedInstance(protocol);
        return syslogServer;
    }

    protected static void startSyslogServers(String host) throws InterruptedException {
        udpServer = createAndStartSyslogInstance(
                new UDPSyslogServerConfig(),
                host,
                SYSLOG_UDP_PORT,
                SyslogConstants.UDP);
        tcpServer = createAndStartSyslogInstance(
                new TCPSyslogServerConfig(),
                host,
                SYSLOG_TCP_PORT,
                SyslogConstants.TCP);
        // reconnection timeout is 5sec for TCP syslog handler
        Thread.sleep(6 * ADJUSTED_SECOND);
    }

    protected static void stopSyslogServers() throws InterruptedException {
        SyslogServer.shutdown();
        if (udpServer != null) {
            udpServer.setThread(null);
            udpServer.getConfig().removeAllEventHandlers();
        }
        if (tcpServer != null) {
            tcpServer.setThread(null);
            tcpServer.getConfig().removeAllEventHandlers();
        }
    }

    protected void makeLog() throws Exception {
        int statusCode = getResponse("someLogMessage");
        Assert.assertTrue("Invalid response statusCode: " + statusCode, statusCode == HttpStatus.SC_OK);
    }

    /**
     * This is expected behavior when syslog is offline. When syslog goes down then at least two TCP messages are needed for
     * success reconnect and even number of UDP messages is needed as well.
     *
     * @throws Exception
     */
    protected void makeLog_syslogIsOffline() throws Exception {
        makeLog();
        makeLog();
    }
}
