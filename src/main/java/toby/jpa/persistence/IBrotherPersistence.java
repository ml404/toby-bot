package toby.jpa.persistence;

import org.springframework.data.repository.CrudRepository;
import toby.jpa.dto.BrotherDto;

import java.util.List;

public interface IBrotherPersistence extends CrudRepository<BrotherDto, Long> {
}
