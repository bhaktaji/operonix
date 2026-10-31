package com.operonix.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.util.List;

/**
 * Serves the built React application from the same origin as the API.
 *
 * <p>Cloud Run runs one container that serves both the static SPA bundle and {@code /api/v1}. The
 * frontend uses client-side routing, so an unmatched path such as {@code /quotes/42} must return
 * {@code index.html} rather than a 404. The fallback is deliberately narrow:
 *
 * <ul>
 *   <li>paths under the API, actuator, OpenAPI and error surfaces are never rewritten, so a bad API
 *       URL still returns a JSON problem document instead of HTML;</li>
 *   <li>a missing file with an extension (a stale {@code /assets/*.js} after a rollout) returns 404
 *       instead of HTML, which would otherwise surface as a confusing MIME-type error in the
 *       browser.</li>
 * </ul>
 *
 * <p>The static location is configurable ({@code operonix.web.static-locations}) so integration
 * tests can point at a fixture instead of a real bundle. In the container image the bundle is
 * copied into {@code classpath:/static/} at build time.*/
@Configuration
public class WebStaticConfig implements WebMvcConfigurer {

    /** Path prefixes owned by the server; these must never fall back to the SPA shell. */
    private static final List<String> SERVER_PREFIXES = List.of(
            "api/", "actuator/", "v3/", "swagger-ui/", "error", "webjars/");

    private final String[] staticLocations;

    public WebStaticConfig(OperonixProperties properties) {
        this.staticLocations = properties.web().staticLocations().toArray(String[]::new);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(staticLocations)
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    /** Resolves real files, then falls back to {@code index.html} for client-side routes only. */
    static class SpaFallbackResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            String path = normalize(resourcePath);
            // A path that names something (not the root, not a directory) must exist on disk.
            if (!path.isEmpty() && !path.endsWith("/")) {
                Resource requested = super.getResource(resourcePath, location);
                if (requested != null && requested.exists() && requested.isReadable()) {
                    return requested;
                }
            }
            if (!isSpaRoute(path)) {
                return null;
            }
            Resource index = location.createRelative("index.html");
            return index.exists() && index.isReadable() ? index : null;
        }

        /** Root requests arrive as {@code ""}, {@code "/"} or {@code "."} depending on the path. */
        private static String normalize(String resourcePath) {
            String path = resourcePath;
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            return ".".equals(path) || "./".equals(path) ? "" : path;
        }

        private boolean isSpaRoute(String path) {
            if (SERVER_PREFIXES.stream().anyMatch(path::startsWith)) {
                return false;
            }
            // A missing asset (has an extension) is a genuine 404, not a route. Serving HTML there
            // would surface as a confusing MIME-type error after a rollout changes asset hashes.
            String lastSegment = path.substring(path.lastIndexOf('/') + 1);
            return !lastSegment.contains(".");
        }
    }
}
