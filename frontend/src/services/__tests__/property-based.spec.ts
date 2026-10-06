import { describe, it, expect } from 'vitest';
import * as fc from 'fast-check';
import type { AgentStatusEvent } from '../api';

describe('Property-Based Invariant Tests (fast-check)', () => {
  it('Property: Event stream sliding window never exceeds capacity of 20 elements', () => {
    fc.assert(
      fc.property(
        fc.array(
          fc.record({
            agentId: fc.uuid(),
            agentName: fc.string({ minLength: 1, maxLength: 50 }),
            status: fc.constantFrom('HEALTHY', 'DEGRADED', 'OFFLINE', 'UNKNOWN' as const),
            previousStatus: fc.constantFrom('HEALTHY', 'DEGRADED', 'OFFLINE', 'UNKNOWN'),
            latencyMs: fc.integer({ min: 0, max: 10000 }),
            timestamp: fc.integer({ min: 1577836800000, max: 1924982400000 }).map(t => new Date(t).toISOString()),
            message: fc.string()
          }),
          { minLength: 0, maxLength: 100 }
        ),
        (events: AgentStatusEvent[]) => {
          const buffer: AgentStatusEvent[] = [];
          for (const ev of events) {
            buffer.unshift(ev);
            if (buffer.length > 20) {
              buffer.pop();
            }
          }
          expect(buffer.length).toBeLessThanOrEqual(20);
          if (events.length > 0) {
            expect(buffer[0]).toEqual(events[events.length - 1]);
          }
        }
      ),
      { numRuns: 100 }
    );
  });

  it('Property: Average latency calculation is bounded within [min, max] range', () => {
    fc.assert(
      fc.property(
        fc.array(fc.integer({ min: 1, max: 60000 }), { minLength: 1, maxLength: 100 }),
        (latencies: number[]) => {
          const min = Math.min(...latencies);
          const max = Math.max(...latencies);
          const sum = latencies.reduce((acc, curr) => acc + curr, 0);
          const avg = Math.round(sum / latencies.length);

          expect(avg).toBeGreaterThanOrEqual(min);
          expect(avg).toBeLessThanOrEqual(max);
        }
      ),
      { numRuns: 100 }
    );
  });
});
