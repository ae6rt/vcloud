package org.marketlive.helloworld;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;

public class HelloServlet extends HttpServlet {
    public static final String ID = "id";

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String p = request.getParameter(ID);
        System.out.println("request with id=" + p);

        HttpSession httpSession = request.getSession();
        Object id = httpSession.getAttribute(ID);
        if (id == null) {
            System.out.println("setting new id on session");
            httpSession.setAttribute(ID, p);
            id = httpSession.getAttribute(ID);
        }
        // echo what is actually in the session
        response.getWriter().println("id=" + id.toString());
    }
}
