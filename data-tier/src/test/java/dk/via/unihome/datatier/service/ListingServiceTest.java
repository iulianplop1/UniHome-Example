package dk.via.unihome.datatier.service;

import dk.via.unihome.datatier.model.ListingRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.grpc.server.port=0")
class ListingServiceTest {

  @Autowired
  private ListingService listingService;

  @Test
  void createdListingCanBeFoundById() {
    ListingRecord created = listingService.createListing("Test room", "Created by ListingServiceTest");

    ListingRecord found = listingService.getListingById(created.id());

    assertNotNull(found);
    assertEquals(created.id(), found.id());
    assertEquals("Test room", found.name());
    assertEquals("Created by ListingServiceTest", found.description());
    assertDoesNotThrow(() -> UUID.fromString(created.id()));
  }

  @Test
  void createdListingAppearsInAllListings() {
    ListingRecord created = listingService.createListing("Test flat", "Should be in the list");

    List<ListingRecord> all = listingService.getListings();

    assertTrue(all.stream().anyMatch(listing -> listing.id().equals(created.id())));
  }

  @Test
  void testDataFromSqlScriptIsThere() {
    ListingRecord found = listingService.getListingById("00000000-0000-0000-0000-000000000001");

    assertNotNull(found);
    assertEquals("Room near campus", found.name());
  }

  @Test
  void unknownIdReturnsNull() {
    assertNull(listingService.getListingById(UUID.randomUUID().toString()));
  }

  @Test
  void invalidIdReturnsNull() {
    assertNull(listingService.getListingById("abc"));
  }
}