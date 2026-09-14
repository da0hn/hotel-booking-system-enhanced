package com.hotel.booking.system.customer.service.infrastructure.db.repository;

import com.hotel.booking.system.customer.service.infrastructure.db.entity.CustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface CustomerJpaRepository extends JpaRepository<CustomerEntity, UUID> {
}
