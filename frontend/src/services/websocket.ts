import { Client, type IMessage } from '@stomp/stompjs';
import type { AgentStatusEvent } from './api';

export type StatusEventHandler = (event: AgentStatusEvent) => void;
export type ConnectionEventHandler = (connected: boolean) => void;

export class WebSocketService {
  private client: Client | null = null;
  private listeners: StatusEventHandler[] = [];
  private connectionListeners: ConnectionEventHandler[] = [];
  public isConnected = false;

  public connect(brokerUrl?: string) {
    if (this.client && this.client.active) {
      return;
    }

    const defaultUrl = (window.location.protocol === 'https:' ? 'wss://' : 'ws://') + 
                       (import.meta.env.VITE_WS_HOST || 'localhost:8080') + '/ws';
    const targetUrl = brokerUrl || import.meta.env.VITE_WS_URL || defaultUrl;

    this.client = new Client({
      brokerURL: targetUrl,
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        this.isConnected = true;
        this.notifyConnectionListeners(true);
        console.log('[WebSocket] Connected to A2A Hub broker at:', targetUrl);

        this.client?.subscribe('/topic/agents/status', (message: IMessage) => {
          try {
            const event: AgentStatusEvent = JSON.parse(message.body);
            this.listeners.forEach(fn => fn(event));
          } catch (e) {
            console.error('[WebSocket] Failed to parse agent status event:', e);
          }
        });
      },
      onDisconnect: () => {
        this.isConnected = false;
        this.notifyConnectionListeners(false);
        console.log('[WebSocket] Disconnected from broker');
      },
      onStompError: frame => {
        this.isConnected = false;
        this.notifyConnectionListeners(false);
        console.error('[WebSocket] STOMP Broker error:', frame.headers['message'], frame.body);
      }
    });

    this.client.activate();
  }

  public onStatusChange(callback: StatusEventHandler) {
    this.listeners.push(callback);
    return () => {
      this.listeners = this.listeners.filter(l => l !== callback);
    };
  }

  public onConnectionChange(callback: ConnectionEventHandler) {
    this.connectionListeners.push(callback);
    // Emit immediate current state
    callback(this.isConnected);
    return () => {
      this.connectionListeners = this.connectionListeners.filter(l => l !== callback);
    };
  }

  private notifyConnectionListeners(status: boolean) {
    this.connectionListeners.forEach(fn => fn(status));
  }

  public disconnect() {
    if (this.client) {
      this.client.deactivate();
      this.isConnected = false;
      this.notifyConnectionListeners(false);
    }
  }
}

export const wsService = new WebSocketService();
