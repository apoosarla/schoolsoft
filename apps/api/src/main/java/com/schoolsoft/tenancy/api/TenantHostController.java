package com.schoolsoft.tenancy.api;

import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.tenancy.internal.TenantHostRepository;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Turns the address a browser arrived on into the tenant whose door it is.
 *
 * Reachable without a token — it has to be, since it runs before sign-in — and
 * whitelisted by the {@code /v1/public/**} prefix in {@code SecurityConfig} and
 * {@code TenantResolverFilter}.
 *
 * <p>It resolves; it never enumerates. There is no listing, no search and no
 * prefix match: a caller names one host and gets that host's tenant or a 404.
 * Anyone able to ask already had the hostname, which DNS would have given them
 * anyway, so this adds no disclosure — whereas a school picker would publish
 * the customer list to everyone who opened a login page.</p>
 */
@RestController
@RequestMapping("/v1/public/tenant")
public class TenantHostController {

    private final TenantHostRepository hosts;
    private final String vendorSuffix;

    public TenantHostController(
        TenantHostRepository hosts,
        @Value("${schoolsoft.public.host-suffix:.schoolsoft.app}") String vendorSuffix
    ) {
        this.hosts = hosts;
        this.vendorSuffix = vendorSuffix.toLowerCase(Locale.ROOT);
    }

    @PreAuthorize("permitAll()")
    @GetMapping
    public TenantResolutionDto resolve(@RequestParam String host) {
        String folded = stripPort(host).toLowerCase(Locale.ROOT);
        return hosts.resolve(folded, isOurs(folded))
            .orElseThrow(() -> new NotFoundException("No tenant answers on that address"));
    }

    /**
     * A school on its own domain gets no vendor mark on its login page. Ours
     * are the hosts under the platform suffix, plus localhost, so a dev build
     * keeps showing the Schoolsoft chrome it is there to work on.
     */
    private boolean isOurs(String host) {
        return host.endsWith(vendorSuffix)
            || host.equals("localhost")
            || host.equals("127.0.0.1");
    }

    /** {@code Host} carries a port when the app runs off :443; the registry does not. */
    private static String stripPort(String host) {
        int colon = host.lastIndexOf(':');
        return colon > 0 ? host.substring(0, colon) : host;
    }
}
