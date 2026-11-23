import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { flushPromises } from '@vue/test-utils';
import { useTaskStore } from '../taskStore';
import { tasksApi, type TaskDto } from '../../services/api';
import { wsService, type ConnectionEventHandler, type TaskUpdatedEvent } from '../../services/websocket';

const mockTaskFixture: TaskDto = {
  id: 'task-abc-123',
  agentId: 'agent-1',
  agentName: 'TestAgent',
  contextId: 'ctx-001',
  state: 'SUBMITTED',
  request: { query: 'Analyze data' },
  response: null,
  errorDetail: '',
  createdAt: '2026-10-11T12:00:00Z',
  updatedAt: '2026-10-11T12:00:00Z',
};

describe('useTaskStore Pinia Store', () => {
  let taskUpdateCallback: ((event: TaskUpdatedEvent) => void) | null = null;
  let connectionCallback: ConnectionEventHandler | null = null;

  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    taskUpdateCallback = null;
    connectionCallback = null;

    vi.spyOn(wsService, 'onTaskUpdate').mockImplementation((cb: (event: TaskUpdatedEvent) => void) => {
      taskUpdateCallback = cb;
      return () => {};
    });
    vi.spyOn(wsService, 'onConnectionChange').mockImplementation((cb) => {
      connectionCallback = cb;
      cb(false);
      return () => {};
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('Initial State', () => {
    it('initializes with default state values', () => {
      const store = useTaskStore();
      expect(store.currentTask).toBeNull();
      expect(store.isLoading).toBe(false);
      expect(store.error).toBeNull();
    });
  });

  describe('submitTask Action', () => {
    it('submits task successfully and updates currentTask state', async () => {
      const store = useTaskStore();
      vi.spyOn(tasksApi, 'submitTask').mockResolvedValueOnce({
        data: mockTaskFixture,
      } as any);

      const promise = store.submitTask('agent-1', { query: 'Analyze data' });
      expect(store.isLoading).toBe(true);
      expect(store.error).toBeNull();

      const result = await promise;

      expect(result).toEqual(mockTaskFixture);
      expect(store.currentTask).toEqual(mockTaskFixture);
      expect(store.isLoading).toBe(false);
      expect(store.error).toBeNull();
      expect(tasksApi.submitTask).toHaveBeenCalledWith({
        agentId: 'agent-1',
        payload: { query: 'Analyze data' },
      });
    });

    it('reconciles a completion broadcast before the asynchronous submit response', async () => {
      const store = useTaskStore();
      const completedTask = { ...mockTaskFixture, state: 'COMPLETED', response: { output: 42 } };
      vi.spyOn(tasksApi, 'submitTask').mockImplementationOnce(async () => {
        taskUpdateCallback!({
          type: 'TASK_UPDATED',
          taskId: mockTaskFixture.id,
          agentId: mockTaskFixture.agentId,
          state: 'COMPLETED',
          timestamp: completedTask.updatedAt,
        });
        return { status: 202, data: mockTaskFixture } as any;
      });
      const getTask = vi.spyOn(tasksApi, 'getTask').mockResolvedValueOnce({ data: completedTask } as any);

      await store.submitTask('agent-1', { query: 'Analyze data' }, true);
      await flushPromises();

      expect(getTask).toHaveBeenCalledWith(mockTaskFixture.id);
      expect(store.currentTask).toEqual(completedTask);
    });

    it('preserves a terminal result when the initial asynchronous refresh is stale', async () => {
      const store = useTaskStore();
      const completedTask = { ...mockTaskFixture, state: 'COMPLETED', response: { output: 42 } };
      vi.spyOn(tasksApi, 'submitTask').mockResolvedValueOnce({ status: 202, data: mockTaskFixture } as any);
      let resolveRefresh!: (value: Awaited<ReturnType<typeof tasksApi.getTask>>) => void;
      const staleRefresh = new Promise<Awaited<ReturnType<typeof tasksApi.getTask>>>((resolve) => {
        resolveRefresh = resolve;
      });
      vi.spyOn(tasksApi, 'getTask')
        .mockReturnValueOnce(staleRefresh)
        .mockResolvedValueOnce({ data: completedTask } as any);

      await store.submitTask('agent-1', { query: 'Analyze data' }, true);
      taskUpdateCallback!({
        type: 'TASK_UPDATED',
        taskId: mockTaskFixture.id,
        agentId: mockTaskFixture.agentId,
        state: 'COMPLETED',
        timestamp: completedTask.updatedAt,
      });
      await flushPromises();
      resolveRefresh({ data: { ...mockTaskFixture, state: 'WORKING' } } as any);
      await flushPromises();

      expect(store.currentTask).toEqual(completedTask);
    });

    it('refreshes an in-flight task when the WebSocket connects', async () => {
      const store = useTaskStore();
      store.currentTask = { ...mockTaskFixture, state: 'WORKING' };
      const completedTask = { ...mockTaskFixture, state: 'COMPLETED', response: { output: 42 } };
      const getTask = vi.spyOn(tasksApi, 'getTask').mockResolvedValueOnce({ data: completedTask } as any);

      connectionCallback?.(true);
      await flushPromises();

      expect(getTask).toHaveBeenCalledWith(mockTaskFixture.id);
      expect(store.currentTask).toEqual(completedTask);
    });

    it('sets error from response data message and re-throws when submitTask fails', async () => {
      const store = useTaskStore();
      const mockError = {
        response: {
          data: {
            message: 'Target agent is currently offline',
          },
        },
      };
      vi.spyOn(tasksApi, 'submitTask').mockRejectedValueOnce(mockError);

      await expect(
        store.submitTask('agent-1', { query: 'Analyze data' })
      ).rejects.toEqual(mockError);

      expect(store.currentTask).toBeNull();
      expect(store.error).toBe('Target agent is currently offline');
      expect(store.isLoading).toBe(false);
    });

    it('sets error from Error.message when response data message is missing', async () => {
      const store = useTaskStore();
      const genericError = new Error('Network timeout');
      vi.spyOn(tasksApi, 'submitTask').mockRejectedValueOnce(genericError);

      await expect(
        store.submitTask('agent-1', { query: 'Analyze data' })
      ).rejects.toEqual(genericError);

      expect(store.error).toBe('Network timeout');
      expect(store.isLoading).toBe(false);
    });

    it('falls back to default error message when rejection has no message', async () => {
      const store = useTaskStore();
      vi.spyOn(tasksApi, 'submitTask').mockRejectedValueOnce({});

      await expect(
        store.submitTask('agent-1', { query: 'Analyze data' })
      ).rejects.toEqual({});

      expect(store.error).toBe('Failed to submit task');
      expect(store.isLoading).toBe(false);
    });
  });

  describe('WebSocket TASK_UPDATED Event Reactions', () => {
    it('registers onTaskUpdate callback on store creation', () => {
      useTaskStore();
      expect(wsService.onTaskUpdate).toHaveBeenCalledWith(expect.any(Function));
      expect(taskUpdateCallback).not.toBeNull();
    });

    it('updates currentTask state when incoming event matches current task id', () => {
      const store = useTaskStore();
      store.currentTask = { ...mockTaskFixture, state: 'SUBMITTED' };

      const event: TaskUpdatedEvent = {
        type: 'TASK_UPDATED',
        taskId: 'task-abc-123',
        agentId: 'agent-1',
        state: 'RUNNING',
        timestamp: '2026-10-11T12:01:00Z',
      };

      taskUpdateCallback!(event);

      expect(store.currentTask.state).toBe('RUNNING');
    });

    it('refetches full task details via tasksApi.getTask when event state is COMPLETED', async () => {
      const store = useTaskStore();
      store.currentTask = { ...mockTaskFixture, state: 'RUNNING' };

      const completedTask: TaskDto = {
        ...mockTaskFixture,
        state: 'COMPLETED',
        response: { output: 'Computation complete' },
        updatedAt: '2026-10-11T12:02:00Z',
      };

      const getTaskSpy = vi.spyOn(tasksApi, 'getTask').mockResolvedValueOnce({
        data: completedTask,
      } as any);

      const event: TaskUpdatedEvent = {
        type: 'TASK_UPDATED',
        taskId: 'task-abc-123',
        agentId: 'agent-1',
        state: 'COMPLETED',
        timestamp: '2026-10-11T12:02:00Z',
      };

      taskUpdateCallback!(event);

      expect(store.currentTask.state).toBe('COMPLETED');
      expect(getTaskSpy).toHaveBeenCalledWith('task-abc-123');

      // Wait for async fetchTask inside event handler
      await vi.waitFor(() => {
        expect(store.currentTask?.response).toEqual({ output: 'Computation complete' });
      });
    });

    it('refetches full task details via tasksApi.getTask when event state is FAILED', async () => {
      const store = useTaskStore();
      store.currentTask = { ...mockTaskFixture, state: 'RUNNING' };

      const failedTask: TaskDto = {
        ...mockTaskFixture,
        state: 'FAILED',
        errorDetail: 'Execution exception: Out of memory',
        updatedAt: '2026-10-11T12:02:30Z',
      };

      const getTaskSpy = vi.spyOn(tasksApi, 'getTask').mockResolvedValueOnce({
        data: failedTask,
      } as any);

      const event: TaskUpdatedEvent = {
        type: 'TASK_UPDATED',
        taskId: 'task-abc-123',
        agentId: 'agent-1',
        state: 'FAILED',
        timestamp: '2026-10-11T12:02:30Z',
      };

      taskUpdateCallback!(event);

      expect(store.currentTask.state).toBe('FAILED');
      expect(getTaskSpy).toHaveBeenCalledWith('task-abc-123');

      await vi.waitFor(() => {
        expect(store.currentTask?.errorDetail).toBe('Execution exception: Out of memory');
      });
    });

    it('does not update currentTask or refetch if incoming event is for a different taskId', () => {
      const store = useTaskStore();
      store.currentTask = { ...mockTaskFixture, state: 'SUBMITTED' };
      const getTaskSpy = vi.spyOn(tasksApi, 'getTask');

      const event: TaskUpdatedEvent = {
        type: 'TASK_UPDATED',
        taskId: 'task-different-999',
        agentId: 'agent-2',
        state: 'COMPLETED',
        timestamp: '2026-10-11T12:05:00Z',
      };

      taskUpdateCallback!(event);

      expect(store.currentTask.state).toBe('SUBMITTED');
      expect(getTaskSpy).not.toHaveBeenCalled();
    });

    it('handles event safely when currentTask is null', () => {
      const store = useTaskStore();
      store.currentTask = null;
      const getTaskSpy = vi.spyOn(tasksApi, 'getTask');

      const event: TaskUpdatedEvent = {
        type: 'TASK_UPDATED',
        taskId: 'task-abc-123',
        agentId: 'agent-1',
        state: 'COMPLETED',
        timestamp: '2026-10-11T12:05:00Z',
      };

      expect(() => taskUpdateCallback!(event)).not.toThrow();
      expect(getTaskSpy).not.toHaveBeenCalled();
    });
  });

  describe('fetchTask Action', () => {
    it('fetches and updates currentTask on demand', async () => {
      const store = useTaskStore();
      vi.spyOn(tasksApi, 'getTask').mockResolvedValueOnce({
        data: mockTaskFixture,
      } as any);

      const result = await store.fetchTask('task-abc-123');

      expect(result).toEqual(mockTaskFixture);
      expect(store.currentTask).toEqual(mockTaskFixture);
      expect(tasksApi.getTask).toHaveBeenCalledWith('task-abc-123');
    });

    it('logs error and returns undefined when fetchTask fails without throwing', async () => {
      const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
      const store = useTaskStore();
      const mockError = new Error('Task not found');
      vi.spyOn(tasksApi, 'getTask').mockRejectedValueOnce(mockError);

      const result = await store.fetchTask('task-nonexistent');

      expect(result).toBeUndefined();
      expect(consoleSpy).toHaveBeenCalledWith('Failed to fetch task details:', mockError);
    });
  });

  describe('clearTask Action', () => {
    it('resets currentTask and error to null', () => {
      const store = useTaskStore();
      store.currentTask = { ...mockTaskFixture };
      store.error = 'Some error occurred';

      store.clearTask();

      expect(store.currentTask).toBeNull();
      expect(store.error).toBeNull();
    });
  });
});
