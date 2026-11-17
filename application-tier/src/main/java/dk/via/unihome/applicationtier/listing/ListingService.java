package dk.via.unihome.applicationtier.listing;

import dk.via.unihome.applicationtier.client.ListingClient;
import dk.via.unihome.grpc.Listing;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ListingService {

    private final ListingClient listingClient;

    public ListingDto createListing(CreateListingDto dto) {
        return toDto(listingClient.createListing(dto.getName(), dto.getDescription()));
    }

    public List<ListingDto> getListings() {
        return listingClient.getListings().stream().map(ListingService::toDto).toList();
    }

    public Optional<ListingDto> getListing(String id) {
        return listingClient.getListing(id).map(ListingService::toDto);
    }

    private static ListingDto toDto(Listing listing) {
        return new ListingDto(listing.getId(), listing.getName(), listing.getDescription());
    }
}
