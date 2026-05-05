package com.cortex.cortex_common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.cortex.cortex_common.model.User;

@Repository
public interface UserRepository extends JpaRepository<User, String> {
}
