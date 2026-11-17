package dk.via.unihome.applicationtier.client;

import dk.via.unihome.grpc.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

// Wraps the generated stub, so the REST layer doesn't have to build request messages.
@Service
public class ListingClient
{

  private final ListingServiceGrpc.ListingServiceBlockingStub stub;

  public ListingClient(ListingServiceGrpc.ListingServiceBlockingStub stub)
  {
    this.stub = stub;
  }

  public Listing createListing(String name, String description)
  {
    CreateListingRequest request = CreateListingRequest.newBuilder()
        .setName(name).setDescription(description).build();
    return stub.createListing(request);
  }

  public List<Listing> getListings()
  {
    GetListingsRequest request = GetListingsRequest.newBuilder().build();
    GetListingsResponse response = stub.getListings(request);
    return response.getListingsList();
  }

  public Optional<Listing> getListing(String id)
  {
    GetListingRequest request = GetListingRequest.newBuilder().setId(id)
        .build();
    try {
      return Optional.of(stub.getListing(request));
    } catch (StatusRuntimeException exception) {
      if (exception.getStatus().getCode() == Status.Code.NOT_FOUND) {
        return Optional.empty();
      }
      throw exception;
    }
  }
}
