package dk.via.unihome.datatier.repository;

import dk.via.unihome.datatier.model.ListingEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Same listing storage contract as the PoC, using the example's JDBC connection. */
@Repository
public class ListingRepository {
    private final JdbcClient db;
    private static final RowMapper<ListingEntity> ROW = (rs, index) -> {
        var result = new ListingEntity();
        result.setId(rs.getObject("id", UUID.class));
        result.setName(rs.getString("name"));
        result.setDescription(rs.getString("description"));
        return result;
    };

    public ListingRepository(JdbcClient db) { this.db = db; }

    public ListingEntity save(ListingEntity entity) {
        return db.sql("INSERT INTO listing(name,description) VALUES (:name,:description) RETURNING id,name,description")
            .param("name", entity.getName()).param("description", entity.getDescription()).query(ROW).single();
    }

    public List<ListingEntity> findAll() {
        return db.sql("SELECT id,name,description FROM listing ORDER BY id").query(ROW).list();
    }

    public Optional<ListingEntity> findById(UUID id) {
        return db.sql("SELECT id,name,description FROM listing WHERE id=:id").param("id", id).query(ROW).optional();
    }
}

