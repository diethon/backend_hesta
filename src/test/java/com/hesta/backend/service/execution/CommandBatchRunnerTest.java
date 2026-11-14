package com.hesta.backend.service.execution;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.entity.Device;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.service.BehaviorEventRecorder;
import com.hesta.backend.service.DeviceCommandService;
import com.hesta.backend.service.execution.CommandBatchRunner.PlannedCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CommandBatchRunnerTest {
    private final DeviceCommandService commands = mock(DeviceCommandService.class);
    private final CommandBatchRunner runner = new CommandBatchRunner(commands, mock(BehaviorEventRecorder.class));

    private static CompletableFuture<CommandResult> ok() {
        return CompletableFuture.completedFuture(CommandResult.builder().success(true).status("SUCCESS").build());
    }

    private static PlannedCommand planned(Device device, String action) {
        return new PlannedCommand(device, action, Map.of(), CommandBatchRunner.detail(device.getId(), action));
    }

    @Test
    void slowDeviceDoesNotHoldBackOtherDevicesAndEachDeviceKeepsItsOrder() throws Exception {
        Device slow = Device.builder().id(UUID.randomUUID()).build();
        Device fast = Device.builder().id(UUID.randomUUID()).build();
        CompletableFuture<CommandResult> slowAck = new CompletableFuture<>();
        CountDownLatch fastDone = new CountDownLatch(1);
        when(commands.sendCommand(eq(slow.getId()), eq("TURN_ON"), any(), any())).thenReturn(slowAck);
        when(commands.sendCommand(eq(slow.getId()), eq("SET_BRIGHTNESS"), any(), any())).thenReturn(ok());
        when(commands.sendCommand(eq(fast.getId()), eq("TURN_ON"), any(), any())).thenReturn(ok());
        when(commands.sendCommand(eq(fast.getId()), eq("SET_BRIGHTNESS"), any(), any())).thenAnswer(invocation -> {
            fastDone.countDown();
            return ok();
        });
        List<PlannedCommand> plan = List.of(planned(slow, "TURN_ON"), planned(slow, "SET_BRIGHTNESS"),
                planned(fast, "TURN_ON"), planned(fast, "SET_BRIGHTNESS"));
        AtomicInteger callbacks = new AtomicInteger();

        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(
                () -> runner.run(plan, StateChangeSource.SCENE, callbacks::incrementAndGet));

        // The fast device reaches its 2nd command while the slow device is still waiting for its 1st ACK.
        assertThat(fastDone.await(2, TimeUnit.SECONDS)).isTrue();
        verify(commands, never()).sendCommand(eq(slow.getId()), eq("SET_BRIGHTNESS"), any(), any());
        assertThat(run).isNotDone();

        slowAck.complete(CommandResult.builder().success(true).status("SUCCESS").build());

        assertThat(run.get(2, TimeUnit.SECONDS)).isEqualTo(4);
        assertThat(callbacks.get()).isEqualTo(4);
        assertThat(plan).allMatch(command -> command.detail().get("success").asBoolean());
    }

    @Test
    void failedSendCountsAsFailedRowWithoutStoppingTheDevice() {
        Device device = Device.builder().id(UUID.randomUUID()).build();
        when(commands.sendCommand(eq(device.getId()), eq("TURN_ON"), any(), any())).thenThrow(new RuntimeException("broker down"));
        when(commands.sendCommand(eq(device.getId()), eq("SET_BRIGHTNESS"), any(), any())).thenReturn(ok());
        List<PlannedCommand> plan = List.of(planned(device, "TURN_ON"), planned(device, "SET_BRIGHTNESS"));

        assertThat(runner.run(plan, StateChangeSource.SCENE)).isEqualTo(1);
        assertThat(plan.get(0).detail().get("status").asText()).isEqualTo("FAILED");
        assertThat(plan.get(1).detail().get("success").asBoolean()).isTrue();
    }
}
