## Additional Requirement: Log Both Critical Path AND Actual Build End

The current implementation correctly calculates the **critical path** (longest dependency chain), but we also need to track and log the **actual build end** - the module that finishes last in wall-clock time.

### Why This Matters

These can be different when:
- Thread pool saturation causes modules to wait for available threads
- Scheduling order doesn't perfectly follow the critical path
- A module with shorter dependency chain starts late and finishes after the critical path

### Example Scenario

```
Critical Path (longest dependency chain):
  root(2s) → module-a(45s) → module-b(20s) = 67s completion time

Actual Execution:
  root(2s) → module-c(10s) waits 30s for thread → runs 40s = 82s ← ACTUAL END
```

Here module-c finishes last (T=82s) but is NOT on the critical path.

### Required Changes to BuildStatistics.java

1. **Track actual finish times** (already done via `moduleEndTimes`)

2. **Find the module that finished last** by wall-clock time:
```java
MavenProject actualBuildEnd = moduleEndTimes.entrySet().stream()
    .max(Map.Entry.comparingByValue())
    .map(Map.Entry::getKey)
    .orElse(null);
```

3. **Compare with critical path end**:
```java
boolean criticalPathIsActualEnd = criticalEnd.equals(actualBuildEnd);
```

4. **Log both in the summary**:

```
Critical Path (longest dependency chain):
Total critical path time: 1m 35.8s (62% of total build)
  └─> module-a..................................... 45.2s [BOTTLENECK]
    │  └─> module-b................................. 32.1s
    │    │  └─> module-c............................. 18.5s

Actual Build End (last module to complete):
  Module: module-d
  Finished at: 2m 34.5s (actual build end)
  Build duration: 38.7s
  Started at: 1m 55.8s
  Wait time: 1m 15.3s (scheduling/thread availability delay)
  Dependencies ready at: 40.5s
  
⚠️  Note: module-d finished AFTER the critical path completed.
    This indicates thread starvation or scheduling inefficiency.
    Consider increasing parallelism or optimizing module order.
```

If they're the same:
```
Actual Build End: module-c (same as critical path end) ✓
  This indicates optimal scheduling - the critical path determined build time.
```

### Benefits

This helps identify:
- **Dependency bottlenecks** (critical path)
- **Scheduling inefficiencies** (actual end ≠ critical path end)
- **Thread starvation** (high wait times on actual end module)
- **Parallelism opportunities** (if actual end started much later than dependencies finished)

### Implementation Checklist

- [ ] Find module with maximum `moduleEndTimes` value (actual build end)
- [ ] Calculate when that module's dependencies were ready
- [ ] Calculate scheduling delay (dependencies ready → module start)
- [ ] Log both critical path and actual build end
- [ ] Show comparison and suggest optimizations if they differ
- [ ] Calculate "scheduling efficiency" metric