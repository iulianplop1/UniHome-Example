package dk.via.unihome.applicationtier.demo;

import dk.via.unihome.grpc.DemoReply;
import dk.via.unihome.grpc.DemoRequest;
import dk.via.unihome.grpc.DemoServiceGrpc;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/demo")
public class DemoController {
    private final DemoServiceGrpc.DemoServiceBlockingStub client;

    public DemoController(GrpcChannelFactory channels) {
        client = DemoServiceGrpc.newBlockingStub(channels.createChannel("datatier"));
    }

    @PostMapping(value = "/{action}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> execute(@PathVariable String action,
          @RequestHeader(value = "Authorization", required = false) String authorization,
          @RequestBody(required = false) String json) {
        if (!action.matches("[a-z][a-z-]{0,39}")) return failure(404, "Unknown operation");
        if (json != null && json.length() > 96_000) return failure(413, "Request is too large");
        String bearer = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).strip() : "";
        if (bearer.length() > 160) return failure(401, "Invalid session");
        try {
            DemoReply reply = client.withDeadlineAfter(15, TimeUnit.SECONDS).execute(
                DemoRequest.newBuilder().setAction(action).setBearer(bearer)
                    .setPayloadJson(json == null || json.isBlank() ? "{}" : json).build());
            return ResponseEntity.status(reply.getStatus()).contentType(MediaType.APPLICATION_JSON)
                    .header("Cache-Control", "no-store").body(reply.getPayloadJson());
        } catch (StatusRuntimeException ex) {
            return failure(503, "The data tier is not available. Try again.");
        }
    }

    private static ResponseEntity<String> failure(int code, String message) {
        return ResponseEntity.status(code).contentType(MediaType.APPLICATION_JSON)
            .header("Cache-Control", "no-store").body("{\"error\":\"" + message + "\"}");
    }
}
