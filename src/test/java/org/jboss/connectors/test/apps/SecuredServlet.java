package org.jboss.connectors.test.apps;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Servlet secured by the Elytron EXTERNAL authentication mechanism.
 * Returns the authenticated user's name and the WildFly worker node name
 * as plain text, which tests parse to verify REMOTE_USER propagation.
 *
 * <p>Security constraints are declared in {@code web.xml} with
 * {@code <auth-method>EXTERNAL</auth-method>} and role {@code gooduser}.
 */
@WebServlet("/secured")
public class SecuredServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/plain");
        PrintWriter out = resp.getWriter();
        out.println("user=" + req.getRemoteUser());
        out.println("worker=" + System.getProperty("jboss.node.name", "unknown"));
    }
}
