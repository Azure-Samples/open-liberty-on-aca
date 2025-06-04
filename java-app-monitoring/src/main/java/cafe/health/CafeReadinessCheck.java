package cafe.health;

import cafe.web.rest.CafeResource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.health.Readiness;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;

@Readiness
@ApplicationScoped
public class CafeReadinessCheck implements HealthCheck {

    private static final String READINESS_CHECK = CafeResource.class.getSimpleName() + " Readiness Check";

    private boolean isHealthy() {
        try {
            String url = "http://localhost:9080/rest/coffees";
            return ClientBuilder.newClient().target(url).request(MediaType.APPLICATION_JSON).get().getStatus() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public HealthCheckResponse call() {
        return isHealthy() ? HealthCheckResponse.up(READINESS_CHECK) : HealthCheckResponse.down(READINESS_CHECK);
    }
}
