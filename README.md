# Restaurant Management System

A Java-based **Restaurant Management System** built using **Hexagonal Architecture**, designed to model real-world restaurant operations with a strong focus on **concurrency, testing, and code quality**.

The project includes synchronous and asynchronous services, permission chains, concurrent processing, benchmarking, and extensive automated testing.

---

## Architecture Overview

This project follows **Hexagonal (Ports & Adapters) Architecture**, separating:

- Core domain logic
- Application services
- Infrastructure & adapters
- External interfaces

Architectural patterns used:

- Ports & Adapters
- Chain of Responsibility
- Concurrency control (locks, optimistic reads)
- In-memory repositories for testing
- Clear separation of concerns across layers

## Project Structure

```
src/
├── main/
│   ├── java/
│   │   └── com/university/restaurant/
│   │       ├── chain/                 # Permission chains (analytics, inventory, menu, etc.)
│   │       ├── infrastructure/        # Controllers, DTOs, JPA adapters, config
│   │       ├── model/                 # Domain models (order, menu, payment, reservation)
│   │       ├── port/                  # Service interfaces (ports)
│   │       ├── repository/            # In-memory & repository abstractions
│   │       ├── service/               # Core application services
│   │       │   └── concurrent/        # Concurrent & async services
│   │       └── RestaurantApplication.java
│   │
│   └── resources/
│       └── application.properties
│
├── test/
│   └── java/
│       └── com/university/restaurant/
│           ├── concurrent/            # Concurrency & deadlock tests
│           ├── model/                 # Unit tests for domain models
│           ├── property/              # Property-based tests
│           └── service/               # Service-layer tests
│
└── jmh/
    └── java/
        └── com/university/restaurant/benchmark/
            └── JMH benchmark tests
```

## Technologies Used

- Java (OpenJDK 21)
- Maven
- JUnit 5
- Mockito
- JMH (Java Microbenchmark Harness)
- JaCoCo (code coverage)
- PIT (mutation testing)
- PMD (static analysis)
- Checkstyle (code style)
- SpotBugs (bug detection)

---

## Testing Strategy

### Unit Testing

- Domain models
- Application services
- Permission chains

### Concurrency Testing

- Deadlock prevention
- Lock contention
- Parallel reads and writes
- Safe table transfers

### Property-Based Testing

- Inventory constraints
- Order validation rules
- Reservation invariants

### Benchmarking

- Data structure performance
- Locking strategies
- Queue throughput
- Decorator overhead

---

## Code Quality & Reports

Code quality and test reports are generated using Maven plugins and are available under:

target/site/

yaml
Copy code

Key reports include:

- JaCoCo coverage report
- PMD static analysis report
- Checkstyle violations report
- SpotBugs bug analysis report
- PIT mutation testing report

---

## How to Run

### Build and Verify

```
mvn clean verify
```

### Run Tests

```
mvn test
```

### Generate Coverage Report

```
mvn jacoco:report
```

### Run Mutation Testing

```
mvn org.pitest:pitest-maven:mutationCoverage
```

---

### Key Features

Thread-safe reservation handling
Deadlock-safe table transfers
Permission-based access control
Asynchronous order and analytics processing
Extensive automated testing
High code coverage with mutation testing
