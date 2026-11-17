package dk.via.unihome.applicationtier.listing;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ListingDto {
    private final String id;
    private final String name;
    private final String description;
}