import { defineStore } from 'pinia';
import { onScopeDispose, ref } from 'vue';
import { tasksApi, type TaskDto } from '../services/api';
import { wsService, type TaskUpdatedEvent } from '../services/websocket';

const terminalStates = new Set(['COMPLETED', 'FAILED', 'CANCELLED']);

function isEarlierState(state: string, currentState: string): boolean {
  return state !== currentState && (terminalStates.has(currentState) || state === 'SUBMITTED');
}

export const useTaskStore = defineStore('taskStore', () => {
  const currentTask = ref<TaskDto | null>(null);
  const isLoading = ref(false);
  const error = ref<string | null>(null);

  const submitTask = async (agentId: string, payload: Record<string, unknown>, runAsynchronously?: boolean) => {
    isLoading.value = true;
    error.value = null;
    try {
      const response = await tasksApi.submitTask({ agentId, payload, async: runAsynchronously });
      currentTask.value = response.data;
      if (response.status === 202) {
        // A fast worker can publish progress before the browser receives the task ID.
        void fetchTask(response.data.id, true);
      }
      return response.data;
    } catch (err: any) {
      error.value = err.response?.data?.message || err.message || 'Failed to submit task';
      throw err;
    } finally {
      isLoading.value = false;
    }
  };

  const fetchTask = async (taskId: string, refreshCurrent = false) => {
    try {
      const response = await tasksApi.getTask(taskId);
      if (refreshCurrent) {
        const task = currentTask.value;
        if (task?.id !== taskId || isEarlierState(response.data.state, task.state)) {
          return;
        }
      }
      currentTask.value = response.data;
      return response.data;
    } catch (err: any) {
      console.error('Failed to fetch task details:', err);
    }
  };

  const clearTask = () => {
    currentTask.value = null;
    error.value = null;
  };

  onScopeDispose(wsService.onTaskUpdate((event: TaskUpdatedEvent) => {
    const task = currentTask.value;
    if (task?.id !== event.taskId || isEarlierState(event.state, task.state)) {
      return;
    }
    task.state = event.state;
    if (terminalStates.has(event.state)) {
      void fetchTask(event.taskId, true);
    }
  }));

  onScopeDispose(wsService.onConnectionChange((connected) => {
    const task = currentTask.value;
    if (connected && task && !terminalStates.has(task.state)) {
      void fetchTask(task.id, true);
    }
  }));

  return {
    currentTask,
    isLoading,
    error,
    submitTask,
    fetchTask,
    clearTask
  };
});
