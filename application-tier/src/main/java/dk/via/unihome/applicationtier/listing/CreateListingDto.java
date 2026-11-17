package dk.via.unihome.applicationtier.listing;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CreateListingDto {
    private String name;
    private String description;
}