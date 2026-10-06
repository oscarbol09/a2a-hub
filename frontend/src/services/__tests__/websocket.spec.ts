import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { IMessage, IFrame } from '@stomp/stompjs';
import { WebSocketService, wsService } from '../websocket';
import type { AgentStatusEvent } from '../api';

let capturedClientOptions: any = null;
let mockSubscribeCallback: ((message: IMessage) => void) | null = null;
let clientInstances: any[] = [];

vi.mock('@stomp/stompjs', () => {
  return {
    Client: vi.fn().mockImplementation(function (options: any) {
      capturedClientOptions = options;
      const instance = {
        active: false,
        options,
        activate: vi.fn(function () {
          instance.active = true;
        }),
        deactivate: vi.fn(function () {
          instance.active = false;
        }),
        subscribe: vi.fn(function (_topic: string, callback: (message: IMessage) => void) {
          mockSubscribeCallback = callback;
          return { id: 'sub-0', unsubscribe: vi.fn() };
        }),
      };
      clientInstances.push(instance);
      return instance;
    }),
  };
});

describe('WebSocketService', () => {
  let originalLocation: any;
  let service: WebSocketService;

  beforeEach(() => {
    vi.clearAllMocks();
    capturedClientOptions = null;
    mockSubscribeCallback = null;
    clientInstances = [];
    service = new WebSocketService();
    originalLocation = window.location;
  });

  afterEach(() => {
    service.disconnect();
    (window as any).location = originalLocation;
  });

  describe('Broker URL Determination & Environment Strategy', () => {
    it('prioritizes explicit brokerUrl passed directly to connect()', () => {
      service.connect('wss://custom.hub.internal/ws');

      expect(capturedClientOptions).not.toBeNull();
      expect(capturedClientOptions.brokerURL).toBe('wss://custom.hub.internal/ws');
    });

    it('falls back to VITE_WS_URL environment variable when provided without explicit argument', () => {
      const originalEnv = import.meta.env.VITE_WS_URL;
      import.meta.env.VITE_WS_URL = 'wss://env.hub.internal/ws';

      try {
        service.connect();
        expect(capturedClientOptions.brokerURL).toBe('wss://env.hub.internal/ws');
      } finally {
        import.meta.env.VITE_WS_URL = originalEnv;
      }
    });

    it('constructs wss:// default URL when protocol is https: and env vars are absent', () => {
      delete (window as any).location;
      (window as any).location = { protocol: 'https:' };

      const originalWsUrl = import.meta.env.VITE_WS_URL;
      const originalWsHost = import.meta.env.VITE_WS_HOST;
      delete (import.meta.env as any).VITE_WS_URL;
      delete (import.meta.env as any).VITE_WS_HOST;

      try {
        service.connect();
        expect(capturedClientOptions.brokerURL).toBe('wss://localhost:8080/ws');
      } finally {
        import.meta.env.VITE_WS_URL = originalWsUrl;
        import.meta.env.VITE_WS_HOST = originalWsHost;
      }
    });

    it('constructs ws:// default URL with custom VITE_WS_HOST when protocol is http:', () => {
      delete (window as any).location;
      (window as any).location = { protocol: 'http:' };

      const originalWsUrl = import.meta.env.VITE_WS_URL;
      const originalWsHost = import.meta.env.VITE_WS_HOST;
      delete (import.meta.env as any).VITE_WS_URL;
      (import.meta.env as any).VITE_WS_HOST = 'custom-cluster:9090';

      try {
        service.connect();
        expect(capturedClientOptions.brokerURL).toBe('ws://custom-cluster:9090/ws');
      } finally {
        import.meta.env.VITE_WS_URL = originalWsUrl;
        import.meta.env.VITE_WS_HOST = originalWsHost;
      }
    });
  });

  describe('Connection Lifecycle & Idempotency', () => {
    it('activates client and sets standard STOMP heartbeat and reconnect delays', () => {
      service.connect('ws://localhost:8080/ws');

      expect(capturedClientOptions.reconnectDelay).toBe(5000);
      expect(capturedClientOptions.heartbeatIncoming).toBe(10000);
      expect(capturedClientOptions.heartbeatOutgoing).toBe(10000);
      expect(clientInstances[0].activate).toHaveBeenCalledTimes(1);
    });

    it('ignores subsequent connect() calls when client is already active (idempotency guard)', () => {
      service.connect('ws://localhost:8080/ws');
      expect(clientInstances).toHaveLength(1);

      // Simulate client being active
      clientInstances[0].active = true;

      service.connect('ws://localhost:8080/ws');
      expect(clientInstances).toHaveLength(1); // No new instance created
    });

    it('deactivates active client and resets isConnected to false on disconnect()', () => {
      service.connect('ws://localhost:8080/ws');
      capturedClientOptions.onConnect();
      expect(service.isConnected).toBe(true);

      service.disconnect();

      expect(clientInstances[0].deactivate).toHaveBeenCalledTimes(1);
      expect(service.isConnected).toBe(false);
    });

    it('handles disconnect() gracefully when no client was ever created', () => {
      expect(() => service.disconnect()).not.toThrow();
      expect(service.isConnected).toBe(false);
    });
  });

  describe('Event Subscriptions & Message Streaming', () => {
    const mockEventFixture: AgentStatusEvent = {
      agentId: '550e8400-e29b-41d4-a716-446655440000',
      agentName: 'WeatherAgent',
      status: 'HEALTHY',
      previousStatus: 'DEGRADED',
      latencyMs: 38,
      timestamp: '2026-10-05T19:20:00Z',
      message: 'Agent health check passed with 200 OK',
    };

    it('subscribes to /topic/agents/status and dispatches parsed events to all registered listeners upon onConnect', () => {
      const listenerA = vi.fn();
      const listenerB = vi.fn();

      service.onStatusChange(listenerA);
      service.onStatusChange(listenerB);

      service.connect('ws://localhost:8080/ws');

      // Trigger onConnect callback
      capturedClientOptions.onConnect();
      expect(service.isConnected).toBe(true);
      expect(clientInstances[0].subscribe).toHaveBeenCalledWith(
        '/topic/agents/status',
        expect.any(Function)
      );

      // Simulate incoming broker message
      const mockMessage: IMessage = {
        body: JSON.stringify(mockEventFixture),
        headers: {},
        ack: vi.fn(),
        nack: vi.fn(),
      } as any;

      mockSubscribeCallback!(mockMessage);

      expect(listenerA).toHaveBeenCalledTimes(1);
      expect(listenerA).toHaveBeenCalledWith(mockEventFixture);
      expect(listenerB).toHaveBeenCalledTimes(1);
      expect(listenerB).toHaveBeenCalledWith(mockEventFixture);
    });

    it('handles malformed JSON broker messages gracefully with error logging and no listener exceptions', () => {
      const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
      const listener = vi.fn();

      service.onStatusChange(listener);
      service.connect('ws://localhost:8080/ws');
      capturedClientOptions.onConnect();

      const malformedMessage: IMessage = {
        body: '{ malformed json content',
        headers: {},
        ack: vi.fn(),
        nack: vi.fn(),
      } as any;

      expect(() => mockSubscribeCallback!(malformedMessage)).not.toThrow();
      expect(listener).not.toHaveBeenCalled();
      expect(consoleSpy).toHaveBeenCalledWith(
        '[WebSocket] Failed to parse agent status event:',
        expect.any(Error)
      );

      consoleSpy.mockRestore();
    });

    it('unsubscribes specific listener when calling the returned disposal function', () => {
      const listenerA = vi.fn();
      const listenerB = vi.fn();

      const unsubscribeA = service.onStatusChange(listenerA);
      service.onStatusChange(listenerB);

      service.connect('ws://localhost:8080/ws');
      capturedClientOptions.onConnect();

      // Unsubscribe listenerA only
      unsubscribeA();

      const mockMessage: IMessage = {
        body: JSON.stringify(mockEventFixture),
        headers: {},
        ack: vi.fn(),
        nack: vi.fn(),
      } as any;

      mockSubscribeCallback!(mockMessage);

      expect(listenerA).not.toHaveBeenCalled();
      expect(listenerB).toHaveBeenCalledTimes(1);
      expect(listenerB).toHaveBeenCalledWith(mockEventFixture);
    });
  });

  describe('Disconnection and STOMP Broker Error Callbacks', () => {
    it('sets isConnected to false when onDisconnect is triggered by broker', () => {
      service.connect('ws://localhost:8080/ws');
      capturedClientOptions.onConnect();
      expect(service.isConnected).toBe(true);

      capturedClientOptions.onDisconnect();
      expect(service.isConnected).toBe(false);
    });

    it('logs STOMP broker errors when onStompError is triggered', () => {
      const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

      service.connect('ws://localhost:8080/ws');

      const mockErrorFrame: IFrame = {
        command: 'ERROR',
        headers: { message: 'Authentication required for topic' },
        body: 'Invalid JWT credentials provided',
      } as any;

      capturedClientOptions.onStompError(mockErrorFrame);

      expect(consoleSpy).toHaveBeenCalledWith(
        '[WebSocket] STOMP Broker error:',
        'Authentication required for topic',
        'Invalid JWT credentials provided'
      );

      consoleSpy.mockRestore();
    });
  });

  describe('Singleton wsService Instance', () => {
    it('exports a valid default singleton instance of WebSocketService', () => {
      expect(wsService).toBeInstanceOf(WebSocketService);
      expect(typeof wsService.connect).toBe('function');
      expect(typeof wsService.disconnect).toBe('function');
      expect(typeof wsService.onStatusChange).toBe('function');
    });
  });
});
