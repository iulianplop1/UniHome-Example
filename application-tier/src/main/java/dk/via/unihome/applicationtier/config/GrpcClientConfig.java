package dk.via.unihome.applicationtier.config;

import dk.via.unihome.grpc.ListingServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class GrpcClientConfig {

  // Creates a stub connected to the channel named "datatier" (see application.properties).
  // The stub is the object we call methods on - it sends them to the data tier.
  @Bean
  public ListingServiceGrpc.ListingServiceBlockingStub listingStub(GrpcChannelFactory channels) {
    return ListingServiceGrpc.newBlockingStub(channels.createChannel("datatier"));
  }
}
