package com.fitlake.user.infrastructure.persistence.repository

import com.fitlake.user.infrastructure.persistence.entity.UserAccountEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataUserAccountRepository : JpaRepository<UserAccountEntity, UUID>
