package com.hotel.booking.system.customer.service.infrastructure.db.repository;

import com.hotel.booking.system.customer.service.infrastructure.db.entity.ReservationOrderHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ReservationOrderHistoryJpaRepository extends JpaRepository<ReservationOrderHistoryEntity, UUID> {
}
