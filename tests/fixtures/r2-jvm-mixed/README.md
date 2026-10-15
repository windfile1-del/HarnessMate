# R2 JVM Mixed (Java + Kotlin) Test Fixture

This repository fixture validates **Stage R2 (Real JVM Build / 真正 JVM 构建)** under the Vega Safe Linux Runtime. It tests real Groovy DSL Gradle build execution, Kotlin + Java bidirectional compilation, JUnit 4 test execution, incremental compilation cache invalidation, and diagnostic line/column error reporting on Android 10+ ARM64.

---

## 1. Environment & Version Specifications

- **Gradle Version**: 8.5 (Groovy DSL)
- **Kotlin Version**: 2.0.21 (`org.jetbrains.kotlin.jvm`)
- **JDK Target / Toolchain**: Java 17 (`JavaLanguageVersion.of(17)`)
- **Testing Framework**: JUnit 4.13.2
- **Offline & Bundled Launcher**:
  - The runtime host/main agent provisions an isolated `/opt/vega-jvm` Gradle 8.5 distribution accompanied by a pre-seeded local Maven repository cache.
  - The production bundled launcher executes Gradle in offline mode (`--offline`), strictly respecting the pinned `gradle/wrapper/gradle-wrapper.properties` without downloading archives over the network.
  - No wrapper JARs (`gradle-wrapper.jar`) or shell scripts (`gradlew`, `gradlew.bat`) are stored in this repository fixture; the container uses the secure, verified local launcher.

---

## 2. Pinned Wrapper Distribution Verification

The fixture specifies official distribution settings in `gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip
distributionSha256Sum=9d926787066a081739e8200858338b4a69e837c3a821a33aca9db09dd4a41026
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
networkTimeout=10000
validateDistributionUrl=true
```

- **Official SHA-256**: `9d926787066a081739e8200858338b4a69e837c3a821a33aca9db09dd4a41026`
- The launcher validates this exact hash before using any extracted distribution.

---

## 3. Architecture: Mutual References Without Runtime Recursion

The fixture contains mutual references between Java and Kotlin:
- **`src/main/java/dev/wind/fixture/r2/JavaGreetingService.java`**:
  - Directly constructs and invokes `KotlinGreetingFormatter`.
  - Accepts, inspects, and validates Kotlin data class `FixturePayload`.
  - Provides `formatEnvelope(String header, String payload)` invoked by Kotlin.
- **`src/main/kotlin/dev/wind/fixture/r2/KotlinGreetingFormatter.kt`**:
  - Takes `JavaGreetingService` as constructor argument.
  - Invokes `service.formatEnvelope(...)` and `service.validatePayload(...)`.
  - Instantiates `FixturePayload` for Java consumption.
- **Acyclic Execution**:
  - Compilation requires joint cross-language resolution (Kotlin compiles with Java stubs/sources; Java compiles against Kotlin classes).
  - Runtime invocation flows are strictly directed and finite (Java -> Kotlin -> Java helper -> return), eliminating recursion and `StackOverflowError`.

---

## 4. Concrete Offline Commands

Run these commands inside the fixture directory (`tests/fixtures/r2-jvm-mixed`):

### 4.1 Compile Classes
Compiles all Kotlin and Java sources into bytecode:
```bash
gradle --offline classes
```
*Expected Output*: Bytecode emitted to `build/classes/kotlin/main` and `build/classes/java/main`.

### 4.2 Execute Tests
Runs the complete JUnit 4 test suite:
```bash
gradle --offline test
```
*Expected Output*: Test reports generated under `build/reports/tests/test/index.html` with all assertions passing.

### 4.3 Full Project Build
Executes compilation, jar packaging, and test verification:
```bash
gradle --offline build
```
*Expected Output*: Packaged JAR in `build/libs/r2-jvm-mixed-1.0.0.jar`.

### 4.4 Clean Build Artifacts
Removes all generated files:
```bash
gradle --offline clean
```

---

## 5. Verification Workflows

### 5.1 Test Failure and Repair Workflow

1. **Introduce Failure**:
   Edit `src/test/java/dev/wind/fixture/r2/JavaMutualReferenceTest.java`:
   ```java
   // Change line:
   assertEquals("[test-env] HELLO -> Vega", greeting);
   // To:
   assertEquals("[test-env] BROKEN -> Vega", greeting);
   ```
2. **Execute Test**:
   ```bash
   gradle --offline test
   ```
3. **Observe Diagnostic Output**:
   Gradle reports failure with exact test method, assertion diff, and line number:
   ```text
   dev.wind.fixture.r2.JavaMutualReferenceTest > testJavaGreetingDelegationToKotlin FAILED
       org.junit.ComparisonFailure: expected:<...nv] [BROKEN] -> Vega> but was:<...nv] [HELLO] -> Vega>
           at org.junit.Assert.assertEquals(Assert.java:117)
           at dev.wind.fixture.r2.JavaMutualReferenceTest.testJavaGreetingDelegationToKotlin(JavaMutualReferenceTest.java:18)
   ```
4. **Repair and Verify Pass**:
   Revert the assertion back to `"[test-env] HELLO -> Vega"` and re-run:
   ```bash
   gradle --offline test
   ```
   *Result*: `BUILD SUCCESSFUL`.

---

### 5.2 Incremental Compilation & Task Invalidation (`UP-TO-DATE`)

1. **Initial Compilation**:
   ```bash
   gradle --offline classes
   ```
   *Observed Tasks*:
   ```text
   > Task :compileKotlin
   > Task :compileJava
   > Task :classes
   ```
2. **Repeated Run Without Changes**:
   ```bash
   gradle --offline classes
   ```
   *Observed Tasks*:
   ```text
   > Task :compileKotlin UP-TO-DATE
   > Task :compileJava UP-TO-DATE
   > Task :classes UP-TO-DATE
   ```
   Gradle verifies inputs and skips execution entirely.
3. **Source Mutation**:
   Add a new method or modify a method in `src/main/java/dev/wind/fixture/r2/JavaGreetingService.java`:
   ```java
   public int getServiceVersion() { return 1; }
   ```
4. **Re-run Compilation**:
   ```bash
   gradle --offline classes
   ```
   *Observed Tasks*:
   Gradle invalidates the cached outputs for modified sources and recompiles affected tasks:
   ```text
   > Task :compileKotlin UP-TO-DATE (or re-executed if ABI changed)
   > Task :compileJava
   > Task :classes
   ```
