package com.operonix.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the SPA fallback resolution rules (no Spring context needed). */
class WebStaticConfigTest {

    private final WebStaticConfig.SpaFallbackResolver resolver = new WebStaticConfig.SpaFallbackResolver();
    private final ClassPathResource location = new ClassPathResource("spa-fixture/");

    private String resolve(String path) throws IOException {
        var resource = resolver.getResource(path, location);
        return resource == null ? null : resource.getFilename();
    }

    @Test
    @DisplayName("the application root serves the shell")
    void rootServesShell() throws IOException {
        assertThat(resolve("")).isEqualTo("index.html");
        assertThat(resolve(".")).isEqualTo("index.html");
    }

    @Test
    @DisplayName("client-side routes and directories serve the shell")
    void routesServeShell() throws IOException {
        assertThat(resolve("quotes/42")).isEqualTo("index.html");
        assertThat(resolve("team")).isEqualTo("index.html");
        assertThat(resolve("assets/")).isEqualTo("index.html");
    }

    @Test
    @DisplayName("real assets are served as themselves")
    void assetsAreServed() throws IOException {
        assertThat(resolve("assets/app.js")).isEqualTo("app.js");
        assertThat(resolve("index.html")).isEqualTo("index.html");
    }

    @Test
    @DisplayName("missing assets and server paths are not rewritten to the shell")
    void serverPathsAndMissingAssetsAreNotFound() throws IOException {
        assertThat(resolve("assets/app-8f3c1d.js")).isNull();
        assertThat(resolve("api/v1/customers")).isNull();
        assertThat(resolve("actuator/health")).isNull();
        assertThat(resolve("v3/api-docs")).isNull();
        assertThat(resolve("swagger-ui/index.html")).isNull();
        assertThat(resolve("..%2f..%2fapplication.yml")).isNull();
    }
}
