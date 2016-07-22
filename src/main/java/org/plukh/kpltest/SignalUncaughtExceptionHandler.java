package org.plukh.kpltest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SignalUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {
    private static final Logger log = LogManager.getLogger(SignalUncaughtExceptionHandler.class);

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        //Last resort (i.e., in case logging doesn't work, etc)
        System.err.println("Uncaught exception in thread " + t.getName());
        e.printStackTrace();
        log.error("Uncaught exception in thread {}", t.getName(), e);
    }
}
