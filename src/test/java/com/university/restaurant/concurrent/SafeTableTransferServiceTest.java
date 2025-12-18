package com.university.restaurant.concurrent;

import com.university.restaurant.model.reservation.Customer;
import com.university.restaurant.model.reservation.Reservation;
import com.university.restaurant.service.concurrent.SafeTableTransferService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SafeTableTransferService - deadlock prevention + safe locking usage.
 *
 * NOTE:
 * SafeTableTransferService.tryReserveTable(...) uses timeoutSeconds for acquiring the lock,
 * it does NOT "hold the lock" for that duration. So tests should not assume a long lock hold.
 */
class SafeTableTransferServiceTest {

    private SafeTableTransferService service;
    private Customer testCustomer;

    @BeforeEach
    void setUp() {
        service = new SafeTableTransferService();
        testCustomer = new Customer("John Doe", "555-1234", "john@example.com");
    }

    @Test
    void transferReservation_singleTransfer_shouldSucceed() {
        Reservation reservation = new Reservation(
                testCustomer,
                LocalDateTime.now().plusDays(1),
                4
        );

        assertTrue(service.transferReservation(reservation, 5, 10));
    }

    @Test
    void transferReservation_invalidTable_shouldThrowException() {
        Reservation reservation = new Reservation(
                testCustomer,
                LocalDateTime.now().plusDays(1),
                4
        );

        assertThrows(IllegalArgumentException.class,
                () -> service.transferReservation(reservation, 5, 999));
    }

    @Test
    void tryReserveTable_withTimeout_shouldSucceed_onValidTable() {
        Reservation reservation = new Reservation(
                testCustomer,
                LocalDateTime.now().plusDays(1),
                4
        );

        assertTrue(service.tryReserveTable(reservation, 10, 2));
    }

    @Test
    void tryReserveTable_invalidTable_shouldThrowException() {
        Reservation reservation = new Reservation(
                testCustomer,
                LocalDateTime.now().plusDays(1),
                4
        );

        assertThrows(IllegalArgumentException.class,
                () -> service.tryReserveTable(reservation, 999, 1));
    }

    @Test
    void reserveTableWithRetry_validTable_shouldSucceed() {
        Reservation reservation = new Reservation(
                testCustomer,
                LocalDateTime.now().plusDays(2),
                2
        );

        assertTrue(service.reserveTableWithRetry(reservation, 15));
    }

    @Test
    void reserveTableWithRetry_invalidTable_shouldThrowException() {
        Reservation reservation = new Reservation(
                testCustomer,
                LocalDateTime.now().plusDays(2),
                2
        );

        assertThrows(IllegalArgumentException.class,
                () -> service.reserveTableWithRetry(reservation, 999));
    }

    @Test
    void reserveMultipleTables_allAvailable_shouldSucceed() {
        Reservation reservation = new Reservation(
                testCustomer,
                LocalDateTime.now().plusDays(1),
                8
        );

        int[] tables = {7, 5, 6}; // intentionally unsorted; service sorts internally
        assertTrue(service.reserveMultipleTables(reservation, tables));
    }

    @Test
    void reserveMultipleTables_invalidTable_shouldThrowException() {
        Reservation reservation = new Reservation(
                testCustomer,
                LocalDateTime.now().plusDays(1),
                8
        );

        int[] tables = {5, 6, 999};
        assertThrows(IllegalArgumentException.class,
                () -> service.reserveMultipleTables(reservation, tables));
    }

    @Test
    void concurrentTransfers_oppositeDirections_shouldComplete() throws Exception {
        // This test checks "no deadlock" in a practical way:
        // the tasks must complete within a timeout and not throw.
        Reservation res1 = new Reservation(testCustomer, LocalDateTime.now().plusDays(1), 4);
        Reservation res2 = new Reservation(testCustomer, LocalDateTime.now().plusDays(2), 2);

        int threadsPerSide = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threadsPerSide * 2);

        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();

            for (int i = 0; i < threadsPerSide; i++) {
                tasks.add(() -> {
                    for (int j = 0; j < 50; j++) {
                        service.transferReservation(res1, 1, 2);
                    }
                    return true;
                });
                tasks.add(() -> {
                    for (int j = 0; j < 50; j++) {
                        service.transferReservation(res2, 2, 1);
                    }
                    return true;
                });
            }

            List<Future<Boolean>> futures = executor.invokeAll(tasks, 10, TimeUnit.SECONDS);

            // If anything times out, invokeAll returns futures that are cancelled
            for (Future<Boolean> f : futures) {
                assertFalse(f.isCancelled(), "Task timed out/cancelled (possible deadlock or too slow)");
                assertTrue(f.get(), "Task should return true");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void highContention_multipleOperations_shouldFinish() throws InterruptedException {
        // Keep it smaller so it's stable on slower machines.
        int numThreads = 12;
        int opsPerThread = 20;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                try {
                    for (int j = 0; j < opsPerThread; j++) {
                        Reservation res = new Reservation(
                                testCustomer,
                                LocalDateTime.now().plusDays(1),
                                2
                        );

                        int op = random.nextInt(3);
                        if (op == 0) {
                            service.tryReserveTable(res, random.nextInt(1, 26), 1);
                        } else if (op == 1) {
                            int from = random.nextInt(1, 26);
                            int to = random.nextInt(1, 26);
                            if (from != to) {
                                service.transferReservation(res, from, to);
                            }
                        } else {
                            int a = random.nextInt(1, 16);
                            int b = random.nextInt(16, 31);
                            int[] tables = {a, b};
                            service.reserveMultipleTables(res, tables);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(20, TimeUnit.SECONDS),
                "High contention scenario should complete in time (no deadlock)");

        executor.shutdownNow();
    }
}
