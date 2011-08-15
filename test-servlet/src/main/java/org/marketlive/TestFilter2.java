package org.marketlive;

import javax.servlet.*;
import java.io.IOException;

public class TestFilter2 implements Filter {
    public void destroy() {
    }

    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws ServletException, IOException {
        System.out.println("before filter2");
        chain.doFilter(req, resp);
        System.out.println("after filter2");
    }

    public void init(FilterConfig config) throws ServletException {

    }

}
