# Feature: Sequential Test Execution by GroupId

## Summary

This feature adds the ability to make test execution sequential for modules that share the same Maven groupId, 
while still allowing tests from different groupIds to run in parallel.

## Additional Enhancement: Automatic Per-Module test-jar Detection

The turbo builder now **automatically detects per-module** when projects use `maven-jar-plugin` with the `test-jar` goal
and adjusts the phase ordering **individually for each module**. This provides optimal parallelization for mixed scenarios.

### How it works:

**Maven 4 (Per-Module Detection)**:
- During `beforeProjectLifecycleExecution`, the extension checks each module individually for test-jar configuration
- Modules with test-jar use: `compile → test-compile → package → test`
- Modules without test-jar use: `compile → package → test-compile → test` (maximum parallelization)
- Each module is optimized independently based on its needs

**Maven 3 (Global Detection)**:
- During `patchLifecycles`, the extension checks if ANY module has test-jar
- If detected, ALL modules use: `compile → test-compile → package → test`
- This is a Maven 3 limitation - lifecycle phases are global, not per-module
- Still better than manual configuration, but less granular than Maven 4

### Benefits:
- **Per-module optimization** in Maven 4 - maximum parallelization where possible
- No manual configuration required for test-jar projects
- Automatic optimization of parallelization strategy
- Mixed projects (some modules with test-jar, some without) are handled optimally in Maven 4
- Manual override still available via `-DturboTestCompile=true/false`

### Example Multi-Module Scenario (Maven 4):

```
Module A (no test-jar):     compile → package → test-compile → test
Module B (has test-jar):    compile → test-compile → package → test
Module C (no test-jar):     compile → package → test-compile → test
```

Each module uses the optimal phase ordering for its configuration!

## Implementation Details

### New Files

1. **TestExecutionCoordinator.java**
   - Manages test execution synchronization using semaphores per groupId
   - Provides `acquireTestLock()` and `releaseTestLock()` methods
   - Can be enabled/disabled via configuration

2. **TestExecutionCoordinatorTest.java**
   - Comprehensive unit tests for the coordinator
   - Tests sequential execution for same groupId
   - Tests parallel execution for different groupIds
   - Tests disabled state (no blocking)

### Modified Files

1. **TurboBuilderConfig.java**
   - Added `sequentialTestsByGroupId` boolean property
   - Updated constructor to accept the new parameter
   - Added getter method `isSequentialTestsByGroupId()`

2. **CurrentProjectExecution.java**
   - Added `TestExecutionCoordinator` field
   - Updated constructor to accept and store coordinator
   - Modified `doWithCurrentProject()` to pass coordinator

3. **TurboBuilder.java**
   - Creates TestExecutionCoordinator instance based on configuration
   - Passes coordinator to `multiThreadedProjectTaskSegmentBuild()`
   - Passes coordinator to `createBuildCallable()`
   - Coordinator is available throughout module build lifecycle

4. **TurboMojoExecutionListener.java**
   - Added ThreadLocal to track if test lock is acquired
   - Acquires test lock before first test mojo execution
   - Releases test lock after test phase completes
   - Ensures lock is released even on test failure to prevent deadlock

5. **PhaseOrderPatcherTest.java**
   - Updated all TurboBuilderConfig constructor calls to include new parameter

6. **TurboMojosExecutionStrategyMaven3Test.java**
   - Updated doWithCurrentProject call to pass TestExecutionCoordinator

7. **README.md**
   - Added comprehensive documentation for the new feature
   - Includes usage examples and configuration options
   - Explains when and why to use this feature

## Usage

Enable sequential test execution by groupId:

```shell
mvn clean verify -b turbo -T1C -DsequentialTestsByGroupId=true
```

Or add to `.mvn/maven.config`:
```
-bturbo
-T1C
-DsequentialTestsByGroupId=true
```

## Behavior

- **Enabled**: Tests from modules with the same groupId run sequentially
- **Disabled (default)**: All tests can run in parallel regardless of groupId
- Only affects the `test` phase, not `integration-test` or other phases
- Tests from different groupIds always run in parallel
- Package phase and other build phases are unaffected

## Use Cases

This feature is useful when:
- Multiple modules in the same groupId share test databases or external resources
- Test fixtures or test data need to be isolated per groupId
- Integration tests within a groupId have dependencies on each other
- Test execution order matters for modules in the same groupId

## Testing

All existing tests pass, plus 3 new tests:
- `shouldSequentiallyExecuteTestsForSameGroupId` - Verifies sequential execution
- `shouldAllowParallelExecutionForDifferentGroupIds` - Verifies different groupIds run in parallel
- `shouldNotBlockWhenDisabled` - Verifies feature can be disabled

Total test count increased from 5 to 8 tests, all passing.
