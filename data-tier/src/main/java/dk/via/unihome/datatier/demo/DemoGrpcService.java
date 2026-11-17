package dk.via.unihome.datatier.demo;

import dk.via.unihome.grpc.DemoReply;
import dk.via.unihome.grpc.DemoRequest;
import dk.via.unihome.grpc.DemoServiceGrpc;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@GrpcService
public class DemoGrpcService extends DemoServiceGrpc.DemoServiceImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(DemoGrpcService.class);
    private static final TypeReference<Map<String, Object>> INPUT = new TypeReference<>() {};
    private final JsonMapper json = JsonMapper.builder().build();
    private final DemoDomain domain;

    public DemoGrpcService(DemoDomain domain) { this.domain = domain; }

    @Override
    public void execute(DemoRequest req, StreamObserver<DemoReply> observer) {
        Map<String, Object> input;
        try {
            input = json.readValue(req.getPayloadJson(), INPUT);
            if (input == null) throw new IllegalArgumentException("Invalid object");
        } catch (RuntimeException ex) {
            reply(observer, 400, Map.of("error", "Expected a JSON object"));
            return;
        }
        try {
            reply(observer, 200, domain.call(req.getAction(), req.getBearer(), input));
        } catch (DemoDomain.Problem ex) {
            reply(observer, ex.status, Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            LOG.error("Demo operation {} failed ({})", req.getAction(), ex.getClass().getSimpleName());
            reply(observer, 500, Map.of("error", "The operation could not be completed"));
        }
    }

    private void reply(StreamObserver<DemoReply> observer, int status, Map<String, Object> payload) {
        observer.onNext(DemoReply.newBuilder().setStatus(status).setPayloadJson(json.writeValueAsString(payload)).build());
        observer.onCompleted();
    }
}
