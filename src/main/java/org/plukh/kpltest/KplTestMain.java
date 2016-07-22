package org.plukh.kpltest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.joda.time.DateTime;

import java.io.IOException;

public class KplTestMain {
    private static final Logger log = LogManager.getLogger(KplTestMain.class);

    private static final int DEFAULT_PORT = 8080;

    private static Server server;
    private static KPLHandler kplHandler;

    public static void main(String[] args) {
        log.info("KPL Test starting up...");

        installUncaughtExceptionHandler();
        addShutdownHook();
        initKPL();
        initWebServer();
    }

    private static void installUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(new SignalUncaughtExceptionHandler());
    }

    private static void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                log.info("Shutting down Gnip-Splitter...");

                shutdownWebServer();
                shutdownKPL();
                shutdownLogging();

                System.err.println("KPL Test shutdown complete at " + DateTime.now());
            }
        });
    }

    private static void initKPL() {
        kplHandler = new KPLHandler();
        try {
            log.info("Initializing Kinesis Producer Library handler...");
            kplHandler.initialize();
            log.info("Kinesis Producer Library handler initialized successfully");
        } catch (IOException e) {
            log.fatal("Exception when initializing Kinesis Producer Library", e);
        }
    }

    private static void initWebServer() {
        try {
            log.debug("Starting embedded Jetty server...");

            server = new Server(DEFAULT_PORT);
            server.setStopAtShutdown(false);

            ServletContextHandler servletContextHandler = new ServletContextHandler(server, "/", ServletContextHandler.NO_SESSIONS);
            servletContextHandler.addServlet(HealthServlet.class, "/health");

            server.start();
            log.info("Successfully started embedded Jetty server");
        } catch (Exception e) {
            log.fatal("Unable to start embedded Jetty server", e);
            System.exit(1);
        }
    }

    private static void shutdownWebServer() {
        if (server != null && server.isRunning()) try {
            log.debug("Stopping embedded Jetty server");
            server.stop();
            log.info("Successfully stopped Jetty server");
        } catch (Exception e) {
            log.error("Error shutting down embedded Jetty server", e);
        }
    }

    private static void shutdownKPL() {
        if (kplHandler != null) {
            log.info("Shutting down Kinesis Producer Library handler...");
            kplHandler.shutdown();
            log.info("Kinesis Producer Library handler shut down");
        }
    }

    private static void shutdownLogging() {
        if( LogManager.getContext() instanceof LoggerContext) {
            log.info("Shutting down logging...");
            Configurator.shutdown((LoggerContext) LogManager.getContext());
        } else {
            log.warn("Unable to shutdown log4j2");
        }
    }
}
