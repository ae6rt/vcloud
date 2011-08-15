package org.marketlive;

import javax.servlet.*;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class TestFilter implements Filter {

    String timeKey = "time";

    public void destroy() {
    }

    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws ServletException, IOException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) req;
        HttpServletResponse httpServletResponse = (HttpServletResponse) resp;

        Object attribute = httpServletRequest.getAttribute(timeKey);

        System.out.println("before filter1(): " + (attribute != null ? attribute.toString() : "(null)"));
        chain.doFilter(req, httpServletResponse);
        System.out.println("after filter1()");

        System.out.println("is committed: " + httpServletResponse.isCommitted());
        attribute = httpServletRequest.getAttribute(timeKey);
        httpServletResponse.addCookie(new Cookie(timeKey, attribute.toString()));
    }

    public void init(FilterConfig config) throws ServletException {
    }

}
