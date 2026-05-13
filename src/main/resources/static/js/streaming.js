      function connectSse() {
        if (state.sseSource) { try { state.sseSource.close(); } catch (e) {} }
        try {
          const src = new EventSource('/claude-api/events');
          state.sseSource = src;
          src.onopen = () => setConnectionStatus(true);
          src.onerror = () => setConnectionStatus(false);
          src.onmessage = ev => {
            try {
              const msg = JSON.parse(ev.data);
              handleNotification(msg);
            } catch (e) {}
          };
        } catch (e) {
          setConnectionStatus(false);
        }
      }
      function handleNotification(msg) {
        const method = msg && msg.method;
        const params = msg && msg.params;

        // Ignore SSE keep-alive ping and ready events — they must not trigger
        // loadMessages() which would clear historical messages while inProgress.
        if (method === 'ping' || method === 'ready') {
          return;
        }

        // Handle Claude Agent protocol messages.
        // All events that arrive during streaming must be routed here so they
        // do not fall through to the default branch that calls loadMessages().
        if (method === 'stream_delta' || method === 'thinking' || method === 'tool_use' ||
            method === 'stream_end' || method === 'complete' || method === 'error' ||
            method === 'session_created' || method === 'permission_request' ||
            method === 'permission_cancelled' || method === 'connected' ||
            method === 'tool_result' || method === 'pong') {
          handleClaudeAgentNotification(msg);
          return;
        }

        // Refresh threads and messages on most notifications
        if (method && method.startsWith('thread/')) {
          refreshThreads().then(() => { updateLiveOverlay(); if (state.selectedThreadId) loadMessages(state.selectedThreadId); });
        } else if (method && method.startsWith('turn/')) {
          refreshThreads().then(() => { updateLiveOverlay(); if (state.selectedThreadId) loadMessages(state.selectedThreadId); });
        } else if (method === 'server/request/resolved') {
          fetchPendingRequests();
        } else if (method && method.includes('server')) {
          fetchPendingRequests();
        } else if (method === 'connection/restored') {
          state.resumedThreadIds.clear();
          fetchPendingRequests();
          refreshThreads().then(() => { updateLiveOverlay(); if (state.selectedThreadId) { loadMessages(state.selectedThreadId); } });
        } else {
          // Unknown event — log for debugging instead of blindly reloading messages.
          console.warn('[SSE] Unknown event type:', method, msg);
        }
      }
      function handleClaudeAgentNotification(msg) {
        const type = msg.method;
        const params = msg.params || {};
        const sessionId = params.sessionId || state.selectedThreadId;

        if (type === 'session_created') {
          if (params.sessionId && state.recentlyCreatedThreadIds.has(params.sessionId)) {
            state.recentlyCreatedThreadIds.delete(params.sessionId);
          }
          refreshThreads();
          return;
        }

        if (type === 'stream_delta') {
          const content = params.content || '';
          if (sessionId === state.selectedThreadId) {
            // Append streaming content to conversation
            appendStreamingContent(content);
          }
          return;
        }

        if (type === 'thinking') {
          const content = params.content || '';
          if (sessionId === state.selectedThreadId) {
            appendThinkingContent(content);
          }
          return;
        }

        if (type === 'tool_use') {
          const toolName = params.toolName || '';
          if (sessionId === state.selectedThreadId) {
            appendToolUse(toolName, params.toolInput);
          }
          return;
        }

        if (type === 'permission_request') {
          const requestId = params.requestId;
          const toolName = params.toolName || '';
          if (requestId) {
            state.pendingRequests.push({
              id: requestId,
              method: 'item/tool/call',
              params: { toolName, input: params.input },
              threadId: sessionId,
              receivedAtIso: new Date().toISOString()
            });
            if (sessionId === state.selectedThreadId) {
              renderConversation();
            }
          }
          return;
        }

        if (type === 'complete' || type === 'error') {
          if (sessionId) {
            setThreadInProgress(sessionId, false);
          }
          state.liveOverlay = null;
          updateThreadSendButton();
          refreshThreads().then(() => {
            if (state.selectedThreadId) {
              loadMessages(state.selectedThreadId);
            }
          });
          return;
        }

        if (type === 'connected') {
          setConnectionStatus(true);
          return;
        }

        // tool_result arrives after a tool call completes; we let the streaming
        // append it naturally — no need to reload messages here.
        if (type === 'tool_result') {
          return;
        }

        // pong is the keep-alive response from the backend — ignore.
        if (type === 'pong') {
          return;
        }
      }
      function appendStreamingContent(content) {
        const list = els.conversationList;
        if (!state.selectedThreadId) return;
        // Find or create assistant message
        let lastMsg = state.messages[state.messages.length - 1];
        if (!lastMsg || lastMsg.role !== 'assistant') {
          lastMsg = { id: 'a_' + Date.now(), role: 'assistant', text: '', messageType: 'agentMessage' };
          state.messages.push(lastMsg);
        }
        lastMsg.text += content;
        renderConversation();
      }
      function appendThinkingContent(content) {
        if (!state.selectedThreadId) return;
        let lastMsg = state.messages[state.messages.length - 1];
        if (!lastMsg || lastMsg.role !== 'thinking') {
          lastMsg = { id: 'th_' + Date.now(), role: 'thinking', text: '', messageType: 'thinkingMessage' };
          state.messages.push(lastMsg);
        }
        lastMsg.text += content;
        renderConversation();
      }
      function appendToolUse(toolName, toolInput) {
        const toolText = `\n\n**Using tool:** \`${toolName}\`\n`;
        let lastMsg = state.messages[state.messages.length - 1];
        if (!lastMsg || lastMsg.role !== 'assistant') {
          lastMsg = { id: 'a_' + Date.now(), role: 'assistant', text: '', messageType: 'agentMessage' };
          state.messages.push(lastMsg);
        }
        lastMsg.text += toolText;
        renderConversation();
      }
      function startAutoRefresh() {
        if (state.autoRefreshTimer) clearInterval(state.autoRefreshTimer);
        state.autoRefreshTimer = setInterval(() => {
          refreshThreads();
          if (state.selectedThreadId) { loadMessages(state.selectedThreadId); fetchPendingRequests(); }
        }, 4000);
      }
      async function init() {
        state.sidebarCollapsed = localStorage.getItem(LS.sidebarCollapsed) === '1';
        const routeId = parseRoute();
        // Only restore selected thread if URL has /thread/xxx route.
        // Opening http://localhost:3000/ should always go to home page.
        if (routeId) {
          state.selectedThreadId = routeId;
        } else {
          state.selectedThreadId = '';
          localStorage.removeItem(LS.selectedThread);
        }
        els.sidebar.classList.toggle('collapsed', state.sidebarCollapsed);
        setupEvents();
        updateView();
        await refreshThreads();
        if (state.selectedThreadId) {
          if (state.knownThreadIds.has(state.selectedThreadId)) {
            selectThread(state.selectedThreadId);
          } else {
            selectThread('');
          }
        }
        connectSse();
        startAutoRefresh();
      }
      init();
