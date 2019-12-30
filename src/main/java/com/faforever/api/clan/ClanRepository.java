package com.faforever.api.clan;

import com.faforever.api.data.domain.Clan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface ClanRepository extends JpaRepository<Clan, Integer> {

  @Transactional
  Optional<Clan> findOneByName(String name);

  Optional<Clan> findOneByTag(String tag);
}
