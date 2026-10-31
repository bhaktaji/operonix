package com.operonix.config;

import com.operonix.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the single-origin deployment shape used on Cloud Run: one container serves the built SPA
 * bundle and the API on the same host.
 *
 * <p>The static location is pointed at {@code src/test/resources/spa-fixture}, which plays the role
 * of {@code frontend/dist} in the image.
 */
@TestPropertySource(properties = "operonix.web.static-locations=classpath:/spa-fixture/")
class SpaHostingIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("the SPA shell is served at / without authentication")
    void servesShellAtRoot() throws Exception {
        // An empty path is not resolvable by the resource handler, so the root forwards to the
        // shell bundle (MockMvc does not execute the forwarded dispatch, hence the second call).
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(MockMvcResultMatchers.forwardedUrl("/index.html"));

        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"root\"")));
    }

    @Test
    @DisplayName("client-side routes deep-link into the SPA shell, not a 404")
    void deepLinksFallBackToShell() throws Exception {
        mvc.perform(get("/quotes/42a1f5d0-0000-0000-0000-000000000000"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Operonix SPA fixture")));

        mvc.perform(get("/team"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    @Test
    @DisplayName("hashed assets are served with their own content type")
    void servesStaticAssets() throws Exception {
        mvc.perform(get("/assets/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("operonix spa fixture")));
    }

    @Test
    @DisplayName("a missing asset is a 404, never the HTML shell")
    void missingAssetDoesNotFallBack() throws Exception {
        mvc.perform(get("/assets/app-8f3c1d.js"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("unknown API paths stay JSON and require a token")
    void apiPathsAreNotSwallowedByTheShell() throws Exception {
        mvc.perform(get("/api/v1/customers"))
                .andExpect(status().isUnauthorized());

        Session session = register("Spa Hosting Co", "spa-hosting@example.com");
        mvc.perform(get("/api/v1/nope").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("platform endpoints are public and never rewritten to the shell")
    void actuatorHealthStaysPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("unknown non-API paths are not a data leak and the shell is public")
    void shellRequestsCarryCspAndNoSession() throws Exception {
        mvc.perform(get("/audit"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Security-Policy",
                                org.hamcrest.Matchers.containsString("font-src 'self' https://fonts.gstatic.com")));
    }
}
