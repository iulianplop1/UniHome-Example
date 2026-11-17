package dk.via.unihome.datatier.model;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ListingEntity {
    private UUID id;
    private String name;
    private String description = "";
}
