package com.hesta.backend.service.impl;

import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.repository.MemberDeviceAccessRepository;
import com.hesta.backend.repository.MemberRoomAccessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MembersListAccessTest {

    @Mock
    private HomeMemberRepository homeMemberRepository;

    @Mock
    private MemberRoomAccessRepository memberRoomAccessRepository;

    @Mock
    private MemberDeviceAccessRepository memberDeviceAccessRepository;

    @InjectMocks
    private HomeServiceImpl homeService;

    private UUID homeId;
    private UUID ownerId;
    private UUID memberAllId;
    private UUID memberCustomId;

    private Home home;
    private HomeMember ownerMember;
    private HomeMember allMember;
    private HomeMember customMember;

    @BeforeEach
    void setUp() {
        homeId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        memberAllId = UUID.randomUUID();
        memberCustomId = UUID.randomUUID();

        home = Home.builder().id(homeId).name("Nhà Test").build();

        User u1 = User.builder().id(ownerId).fullName("Chủ nhà").email("owner@hesta.vn").build();
        User u2 = User.builder().id(memberAllId).fullName("Thành viên ALL").email("all@hesta.vn").build();
        User u3 = User.builder().id(memberCustomId).fullName("Thành viên CUSTOM").email("custom@hesta.vn").build();

        ownerMember = HomeMember.builder().id(UUID.randomUUID()).home(home).user(u1).role(HomeRole.OWNER).status(MemberStatus.ACTIVE).accessScope(AccessScope.ALL).build();
        allMember = HomeMember.builder().id(UUID.randomUUID()).home(home).user(u2).role(HomeRole.MEMBER).status(MemberStatus.ACTIVE).accessScope(AccessScope.ALL).build();
        customMember = HomeMember.builder().id(UUID.randomUUID()).home(home).user(u3).role(HomeRole.MEMBER).status(MemberStatus.ACTIVE).accessScope(AccessScope.CUSTOM).build();
    }

    @Test
    @DisplayName("Members list - OWNER caller sees accessScope, accessRoomCount, accessDeviceOverrideCount")
    @SuppressWarnings("unchecked")
    void testGetHomeMembers_OwnerCaller_SeesAccessStats() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, ownerId)).thenReturn(Optional.of(ownerMember));
        when(homeMemberRepository.findByHomeIdWithUser(homeId)).thenReturn(List.of(ownerMember, allMember, customMember));

        when(memberRoomAccessRepository.countByMemberId(customMember.getId())).thenReturn(3L);
        when(memberDeviceAccessRepository.countByMemberId(customMember.getId())).thenReturn(1L);

        List<Map<String, Object>> members = (List<Map<String, Object>>) homeService.getHomeMembers(ownerId, homeId);

        assertEquals(3, members.size());

        // Custom member stats
        Map<String, Object> customMap = members.stream()
                .filter(m -> m.get("id").equals(memberCustomId))
                .findFirst().orElseThrow();
        assertEquals("CUSTOM", customMap.get("accessScope"));
        assertEquals(3L, customMap.get("accessRoomCount"));
        assertEquals(1L, customMap.get("accessDeviceOverrideCount"));
        assertEquals("custom@hesta.vn", customMap.get("email"));
    }

    @Test
    @DisplayName("Members list - Non-owner caller does NOT see access stats (fields are omitted)")
    @SuppressWarnings("unchecked")
    void testGetHomeMembers_NonOwnerCaller_OmitsAccessStats() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, memberAllId)).thenReturn(Optional.of(allMember));
        when(homeMemberRepository.findByHomeIdWithUser(homeId)).thenReturn(List.of(ownerMember, allMember, customMember));

        List<Map<String, Object>> members = (List<Map<String, Object>>) homeService.getHomeMembers(memberAllId, homeId);

        assertEquals(3, members.size());

        for (Map<String, Object> map : members) {
            assertFalse(map.containsKey("accessScope"), "accessScope should be omitted for non-owner caller");
            assertFalse(map.containsKey("accessRoomCount"), "accessRoomCount should be omitted for non-owner caller");
            assertFalse(map.containsKey("accessDeviceOverrideCount"), "accessDeviceOverrideCount should be omitted for non-owner caller");
        }
    }

    @Test
    @DisplayName("Members list - CUSTOM member caller does NOT see other members' emails")
    @SuppressWarnings("unchecked")
    void testGetHomeMembers_CustomMemberCaller_OmitsEmails() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, memberCustomId)).thenReturn(Optional.of(customMember));
        when(homeMemberRepository.findByHomeIdWithUser(homeId)).thenReturn(List.of(ownerMember, allMember, customMember));

        List<Map<String, Object>> members = (List<Map<String, Object>>) homeService.getHomeMembers(memberCustomId, homeId);

        for (Map<String, Object> map : members) {
            assertFalse(map.containsKey("email"), "email should be omitted for CUSTOM caller");
            assertNotNull(map.get("fullName"));
            assertNotNull(map.get("role"));
        }
    }
}

