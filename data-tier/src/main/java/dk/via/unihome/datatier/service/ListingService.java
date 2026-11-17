package dk.via.unihome.datatier.service;

import dk.via.unihome.datatier.model.ListingEntity;
import dk.via.unihome.datatier.model.ListingRecord;
import dk.via.unihome.datatier.repository.ListingRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ListingService {

  private final ListingRepository listingRepository;

  public ListingService(ListingRepository listingRepository) {
    this.listingRepository = listingRepository;
  }

  public ListingRecord createListing(String name, String description) {
    var entity = new ListingEntity();
    entity.setName(name);
    entity.setDescription(description);
    return toRecord(listingRepository.save(entity));
  }

  public List<ListingRecord> getListings() {
    return listingRepository.findAll().stream()
        .map(ListingService::toRecord)
        .toList();
  }

  public ListingRecord getListingById(String id) {
    UUID uuid;
    try {
      uuid = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      return null;
    }
    return listingRepository.findById(uuid)
        .map(ListingService::toRecord)
        .orElse(null);
  }

  private static ListingRecord toRecord(ListingEntity entity) {
    return new ListingRecord(entity.getId().toString(), entity.getName(), entity.getDescription());
  }
}