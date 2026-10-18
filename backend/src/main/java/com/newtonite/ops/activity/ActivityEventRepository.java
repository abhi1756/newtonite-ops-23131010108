package com.newtonite.ops.activity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityEventRepository extends JpaRepository<ActivityEvent, Long> {

    long countByItemIdAndType(Long itemId, String type);
}
