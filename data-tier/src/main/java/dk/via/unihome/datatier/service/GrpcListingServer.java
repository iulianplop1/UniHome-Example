package dk.via.unihome.datatier.service;

import dk.via.unihome.datatier.model.ListingRecord;
import dk.via.unihome.grpc.CreateListingRequest;
import dk.via.unihome.grpc.GetListingRequest;
import dk.via.unihome.grpc.GetListingsRequest;
import dk.via.unihome.grpc.GetListingsResponse;
import dk.via.unihome.grpc.Listing;
import dk.via.unihome.grpc.ListingServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class GrpcListingServer extends ListingServiceGrpc.ListingServiceImplBase {
  private static final Logger LOG = LoggerFactory.getLogger(GrpcListingServer.class);

  private final ListingService listingService;

  public GrpcListingServer(ListingService listingService) {
    this.listingService = listingService;
  }

  @Override
  public void createListing(CreateListingRequest request, StreamObserver<Listing> responseObserver) {
    var listing = listingService.createListing(request.getName(), request.getDescription());
    responseObserver.onNext(ListingMapper.toProto(listing));
    LOG.info("Listing [{}] created", listing);
    responseObserver.onCompleted();
  }

  @Override
  public void getListings(GetListingsRequest request, StreamObserver<GetListingsResponse> responseObserver) {
    var listings = listingService.getListings();
    var response = GetListingsResponse.newBuilder();
    for (var listing : listings) {
      response.addListings(ListingMapper.toProto(listing));
    }
    responseObserver.onNext(response.build());
    LOG.info("{} listing(s) retrieved", listings.size());
    responseObserver.onCompleted();
  }
  @Override
  public void getListing(GetListingRequest request, StreamObserver<Listing> responseObserver) {
    var listing = listingService.getListingById(request.getId());
    if (listing == null) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("Listing with id [%s] not found".formatted(request.getId()))
              .asRuntimeException());
      return;
    }
    responseObserver.onNext(ListingMapper.toProto(listing));
    LOG.info("Listing [{}] retrieved", listing);
    responseObserver.onCompleted();
  }

  private static class ListingMapper {
    static Listing toProto(ListingRecord listingRecord) {
      return Listing.newBuilder()
          .setId(listingRecord.id())
          .setName(listingRecord.name())
          .setDescription(listingRecord.description())
          .build();
    }
  }
}
