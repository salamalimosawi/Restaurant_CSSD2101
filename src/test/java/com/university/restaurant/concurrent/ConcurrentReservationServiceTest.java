package com.university.restaurant.concurrent;

import com.university.restaurant.model.reservation.Reservation;
import com.university.restaurant.model.reservation.ReservationStatus;
import com.university.restaurant.model.staff.Manager;
import com.university.restaurant.model.staff.Waiter;
import com.university.restaurant.repository.InMemoryReservationRepo;
import com.university.restaurant.repository.InMemoryRestaurantAuditRepo;
import com.university.restaurant.service.concurrent.ConcurrentReservationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ConcurrentReservationService with StampedLock.
 */
class ConcurrentReservationServiceTest {

    private ConcurrentReservationService service;
    private InMemoryReservationRepo repo;
    private Manager manager;
    private Waiter waiter;

    @BeforeEach
    void setUp() {
        repo = new InMemoryReservationRepo();
        service = new ConcurrentReservationService(
                repo,
                new InMemoryRestaurantAuditRepo()
        );
        manager = new Manager("m1", "Alice");
        waiter = new Waiter("w1", "Bob");
    }

    @Test
    void createReservation_singleThread_shouldSucceed() {
        Reservation reservation = service.createReservation(
                waiter,
                "John Doe",
                "555-1234",
                "john@example.com",
                4,
                LocalDateTime.now().plusDays(1)
        );

        assertNotNull(reservation);
        assertEquals(4, reservation.getPartySize());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
    }

    @Test
    void concurrentCreateReservations_shouldAllSucceed() throws InterruptedException {
        int numThreads = 20;
        int reservationsPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        Set<UUID> createdIds = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < numThreads; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < reservationsPerThread; j++) {
                        Reservation res = service.createReservation(
                                waiter,
                                "Customer-" + threadNum + "-" + j,
                                "555-" + threadNum + j,
                                "email@test.com",
                                2,
                                LocalDateTime.now().plusDays(1)
                        );
                        createdIds.add(res.getId());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS));
        executor.shutdown();

        // All IDs should be unique
        assertEquals(numThreads * reservationsPerThread, createdIds.size());
    }

    @Test
    void cancelReservation_existingReservation_shouldSucceed() {
        Reservation reservation = service.createReservation(
                waiter,
                "Jane Doe",
                "555-5678",
                "jane@example.com",
                2,
                LocalDateTime.now().plusDays(2)
        );

        boolean result = service.cancelReservation(manager, reservation.getId().toString());

        assertTrue(result);
        assertEquals(ReservationStatus.CANCELLED, reservation.getStatus());
    }

    @Test
    void concurrentCancelReservations_shouldMaintainConsistency() throws InterruptedException {
        // Create reservations
        List<Reservation> reservations = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Reservation res = service.createReservation(
                    waiter,
                    "Customer-" + i,
                    "555-" + i,
                    "email@test.com",
                    2,
                    LocalDateTime.now().plusDays(1)
            );
            reservations.add(res);
        }

        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        // Cancel them concurrently
        for (int i = 0; i < numThreads; i++) {
            final int start = i * 5;
            executor.submit(() -> {
                try {
                    for (int j = start; j < start + 5; j++) {
                        service.cancelReservation(
                                manager,
                                reservations.get(j).getId().toString()
                        );
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // Verify all cancelled
        for (Reservation res : reservations) {
            assertEquals(ReservationStatus.CANCELLED, res.getStatus());
        }
    }

    @Test
    void findReservation_whileConcurrentWrites_shouldNotCorrupt() throws InterruptedException {
        Reservation reservation = service.createReservation(
                waiter,
                "Test User",
                "555-0000",
                "test@example.com",
                4,
                LocalDateTime.now().plusDays(1)
        );

        int numReaders = 20;
        int numWriters = 5;
        ExecutorService executor = Executors.newFixedThreadPool(numReaders + numWriters);
        CountDownLatch latch = new CountDownLatch(numReaders + numWriters);
        AtomicBoolean errorOccurred = new AtomicBoolean(false);

        // Readers
        for (int i = 0; i < numReaders; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        Reservation found = service.findReservation(
                                reservation.getId().toString()
                        );
                        assertNotNull(found);
                    }
                } catch (Exception e) {
                    errorOccurred.set(true);
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Writers
        for (int i = 0; i < numWriters; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 20; j++) {
                        service.createReservation(
                                waiter,
                                "Another User",
                                "555-9999",
                                "another@test.com",
                                2,
                                LocalDateTime.now().plusDays(2)
                        );
                    }
                } catch (Exception e) {
                    errorOccurred.set(true);
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS));
        executor.shutdown();

        assertFalse(errorOccurred.get(), "No errors should occur during concurrent reads/writes");
    }

    @Test
    void mixedOperations_highContention_shouldMaintainConsistency() throws InterruptedException {
        int numThreads = 30;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);
        List<Reservation> sharedReservations = Collections.synchronizedList(new ArrayList<>());

        // Pre-create some reservations
        for (int i = 0; i < 20; i++) {
            Reservation res = service.createReservation(
                    waiter,
                    "Initial-" + i,
                    "555-" + i,
                    "init@test.com",
                    2,
                    LocalDateTime.now().plusDays(1)
            );
            sharedReservations.add(res);
        }

        for (int i = 0; i < numThreads; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                try {
                    barrier.await(); // Synchronize start

                    for (int j = 0; j < 20; j++) {
                        int operation = random.nextInt(3);

                        switch (operation) {
                            case 0: // Create
                                Reservation newRes = service.createReservation(
                                        waiter,
                                        "User-" + threadNum + "-" + j,
                                        "555-" + threadNum + j,
                                        "email@test.com",
                                        2,
                                        LocalDateTime.now().plusDays(1)
                                );
                                sharedReservations.add(newRes);
                                break;

                            case 1: // Cancel
                                if (!sharedReservations.isEmpty()) {
                                    Reservation toCancel = sharedReservations.get(
                                            random.nextInt(sharedReservations.size())
                                    );
                                    service.cancelReservation(
                                            manager,
                                            toCancel.getId().toString()
                                    );
                                }
                                break;

                            case 2: // Find
                                if (!sharedReservations.isEmpty()) {
                                    Reservation toFind = sharedReservations.get(
                                            random.nextInt(sharedReservations.size())
                                    );
                                    service.findReservation(toFind.getId().toString());
                                }
                                break;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        // System should still be consistent
        assertFalse(sharedReservations.isEmpty());
    }

    @Test
    void optimisticReads_shouldNotBlockWriters() throws InterruptedException, ExecutionException, TimeoutException {
        Reservation reservation = service.createReservation(
                waiter,
                "Test User",
                "555-0000",
                "test@example.com",
                4,
                LocalDateTime.now().plusDays(1)
        );

        int numReaders = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numReaders + 1);

        long startTime = System.currentTimeMillis();

        // Many concurrent readers
        CompletableFuture<?>[] readers = new CompletableFuture[numReaders];
        for (int i = 0; i < numReaders; i++) {
            readers[i] = CompletableFuture.runAsync(() -> {
                for (int j = 0; j < 50; j++) {
                    service.findReservation(reservation.getId().toString());
                }
            }, executor);
        }

        // One writer
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 10; i++) {
                service.createReservation(
                        waiter,
                        "Writer",
                        "555-9999",
                        "writer@test.com",
                        2,
                        LocalDateTime.now().plusDays(2)
                );
            }
        }, executor);

        CompletableFuture.allOf(readers).get(10, TimeUnit.SECONDS);
        writer.get(10, TimeUnit.SECONDS);

        long duration = System.currentTimeMillis() - startTime;

        executor.shutdown();

        // Should complete quickly with optimistic reads
        assertTrue(duration < 8000,
                "Optimistic reads should allow fast concurrent access");
    }
}
