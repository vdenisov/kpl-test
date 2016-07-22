package org.plukh.kpltest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

public class HealthServlet extends HttpServlet {
    private static final Logger log = LogManager.getLogger(HealthServlet.class);

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //Output health information as plain text
        resp.setContentType("text/plain");
        resp.setCharacterEncoding("UTF8");
        resp.setStatus(200);

        PrintWriter out = resp.getWriter();
        out.println("OK");

        out.flush();
    }
}
