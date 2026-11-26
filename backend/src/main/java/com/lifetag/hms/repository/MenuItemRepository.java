package com.lifetag.hms.repository;

import com.lifetag.hms.entity.MenuItem;
import com.lifetag.hms.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {
    List<MenuItem> findByRoleAndActiveTrueOrderBySortOrderAscIdAsc(Role role);
    List<MenuItem> findAllByOrderByRoleAscSortOrderAscIdAsc();
    boolean existsByRoleAndUrlIgnoreCase(Role role, String url);
    boolean existsByRoleAndUrlIgnoreCaseAndIdNot(Role role, String url, Long id);
    long count();
}
