package com.operonix.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the SPA shell at the application root.
 *
 * <p>A request to {@code /} reaches the resource handler with an empty path, and
 * {@code ResourceHttpRequestHandler} skips resource resolution entirely when the path is empty, so
 * the fallback in {@link WebStaticConfig} cannot cover the root. Forwarding to {@code index.html}
 * lets the same resource handler serve it (correct content type, caching, range support), while
 * every deeper client-side route is handled by the fallback resolver.
 */
@Controller
public class SpaRootController {

    @GetMapping("/")
    public String index() {
        return "forward:/index.html";
    }
}
