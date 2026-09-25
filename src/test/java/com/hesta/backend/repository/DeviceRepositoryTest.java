package com.hesta.backend.repository;

import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.DeviceType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DeviceRepositoryTest {
    @Autowired private UserRepository users;
    @Autowired private HomeRepository homes;
    @Autowired private RoomRepository rooms;
    @Autowired private DeviceRepository devices;

    @Test
    void findsDevicesThroughRoomHomeInNameOrder() {
        User owner = users.save(User.builder().fullName("Owner").email("device-repository@example.com")
                .passwordHash("hash").provider(AuthProvider.LOCAL).build());
        Home home = homes.save(Home.builder().name("Home").createdBy(owner).build());
        Home otherHome = homes.save(Home.builder().name("Other").createdBy(owner).build());
        Room room = rooms.save(Room.builder().home(home).name("Living room").build());
        Room otherRoom = rooms.save(Room.builder().home(otherHome).name("Living room").build());

        Device lamp = saveDevice(room, "Z lamp");
        saveDevice(room, "A fan");
        saveDevice(otherRoom, "Other device");

        assertThat(devices.findAllByRoom_Home_IdOrderByNameAsc(home.getId()))
                .extracting(Device::getName).containsExactly("A fan", "Z lamp");
        assertThat(devices.findByHomeId(home.getId()))
                .extracting(Device::getName).containsExactlyInAnyOrder("A fan", "Z lamp");
        assertThat(devices.findByRoomId(room.getId()))
                .extracting(Device::getName).containsExactlyInAnyOrder("A fan", "Z lamp");
        assertThat(devices.findByIdWithRoomHome(lamp.getId()).orElseThrow()
                .getRoom().getHome().getId()).isEqualTo(home.getId());
    }

    private Device saveDevice(Room room, String name) {
        return devices.save(Device.builder().room(room).name(name).deviceType(DeviceType.LIGHT)
                .status(DeviceStatus.UNKNOWN).currentState(Map.of()).build());
    }
}
