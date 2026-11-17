package dk.via.unihome.datatier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.grpc.server.port=0")
class DataTierApplicationTests {

    @Test
    void contextLoads() {
    }

}
