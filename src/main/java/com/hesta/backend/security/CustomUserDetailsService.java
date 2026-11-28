package com.hesta.backend.security;

import com.hesta.backend.cache.HestaCacheService;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.PlatformRole;
import com.hesta.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private static final String USER_AUTH_NAMESPACE = "auth:user";
    private static final Duration USER_AUTH_TTL = Duration.ofSeconds(60);

    private final UserRepository userRepository;

    @Autowired(required = false)
    private HestaCacheService cacheService;

    public void setCacheService(HestaCacheService cacheService) {
        this.cacheService = cacheService;
    }

    /** Passwordless user snapshot stored in Redis/local cache for JWT authentication. */
    public record CachedUserPrincipal(UUID id, String email, AccountStatus status, PlatformRole platformRole) {}

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy người dùng với email: " + email));

        return CustomUserDetails.build(user);
    }

    @Transactional(readOnly = true)
    public UserDetails loadUserById(UUID userId) {
        if (userId != null && cacheService != null && cacheService.isReadThroughCacheEnabled()) {
            Optional<CachedUserPrincipal> cached =
                    cacheService.get(USER_AUTH_NAMESPACE, userId.toString(), CachedUserPrincipal.class);
            if (cached.isPresent()) {
                CachedUserPrincipal p = cached.get();
                if (p.id() != null && p.email() != null && p.status() != null && p.platformRole() != null) {
                    return new CustomUserDetails(
                            p.id(),
                            p.email(),
                            "",
                            p.status(),
                            List.of(new SimpleGrantedAuthority("ROLE_" + p.platformRole().name()))
                    );
                }
            }
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy người dùng với ID: " + userId));

        if (cacheService != null && cacheService.isReadThroughCacheEnabled()
                && user.getId() != null && user.getEmail() != null
                && user.getStatus() != null && user.getPlatformRole() != null) {
            cacheService.put(
                    USER_AUTH_NAMESPACE,
                    user.getId().toString(),
                    new CachedUserPrincipal(user.getId(), user.getEmail(), user.getStatus(), user.getPlatformRole()),
                    USER_AUTH_TTL
            );
        }

        return CustomUserDetails.build(user);
    }

    public void evictUserCache(UUID userId) {
        if (cacheService != null && userId != null) {
            cacheService.evict(USER_AUTH_NAMESPACE, userId.toString());
        }
    }
}
