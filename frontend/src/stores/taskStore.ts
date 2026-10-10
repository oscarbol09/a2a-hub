import { defineStore } from 'pinia';
import { ref } from 'vue';
import { tasksApi, type TaskDto } from '../services/api';
import { wsService, type TaskUpdatedEvent } from '../services/websocket';

export const useTaskStore = defineStore('taskStore', () => {
  const currentTask = ref<TaskDto | null>(null);
  const isLoading = ref(false);
  const error = ref<string | null>(null);

  // Setup websocket listener
  wsService.onTaskUpdate((event: TaskUpdatedEvent) => {
    if (currentTask.value && currentTask.value.id === event.taskId) {
      currentTask.value.state = event.state;
      // Depending on backend, we might want to refetch the full task 
      // if it contains updated response/error details
      if (['COMPLETED', 'FAILED'].includes(event.state)) {
        fetchTask(event.taskId);
      }
    }
  });

  const submitTask = async (agentId: string, payload: Record<string, any>) => {
    isLoading.value = true;
    error.value = null;
    try {
      const response = await tasksApi.submitTask({ agentId, payload });
      currentTask.value = response.data;
      return response.data;
    } catch (err: any) {
      error.value = err.response?.data?.message || err.message || 'Failed to submit task';
      throw err;
    } finally {
      isLoading.value = false;
    }
  };

  const fetchTask = async (taskId: string) => {
    try {
      const response = await tasksApi.getTask(taskId);
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

  return {
    currentTask,
    isLoading,
    error,
    submitTask,
    fetchTask,
    clearTask
  };
});
