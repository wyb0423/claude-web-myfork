      const LS = {
        selectedThread: 'claude.selectedThread',
        sidebarCollapsed: 'claude.sidebarCollapsed',
      };
      const state = {
        projectGroups: [],
        selectedThreadId: '',
        messages: [],
        isLoadingThreads: false,
        isLoadingMessages: false,
        isSendingMessage: false,
        resumedThreadIds: new Set(),
        isInterrupting: false,
        sidebarCollapsed: false,
        sidebarSearch: '',
        pendingRequests: [],
        liveOverlay: null,
        sseConnected: false,
        autoAllowTimer: null,
        autoAllowRequestId: null,
        autoAllowSeconds: 10,
        autoRefreshTimer: null,
        sseSource: null,
        knownThreadIds: new Set(),
        recentlyCreatedThreadIds: new Set(),
      };
      const els = {
        sidebar: document.getElementById('sidebar'),
        toggleSidebar: document.getElementById('toggle-sidebar'),
        bodyToggleSidebar: document.getElementById('body-toggle-sidebar'),
        newThreadBtn: document.getElementById('new-thread-btn'),
        refreshBtn: document.getElementById('refresh-btn'),
        threadSearch: document.getElementById('thread-search'),
        threadTree: document.getElementById('thread-tree'),
        homeView: document.getElementById('home-view'),
        threadView: document.getElementById('thread-view'),

        homeComposer: document.getElementById('home-composer'),
        homeSend: document.getElementById('home-send'),
        conversationList: document.getElementById('conversation-list'),
        threadComposer: document.getElementById('thread-composer'),
        threadSend: document.getElementById('thread-send'),
        threadInterrupt: document.getElementById('thread-interrupt'),
        imageModal: document.getElementById('image-modal'),
        imageModalImg: document.getElementById('image-modal-img'),
        imageModalClose: document.getElementById('image-modal-close'),
        // connection status updated via querySelectorAll in setConnectionStatus
        userAvatar: document.getElementById('user-avatar'),
        userName:   document.getElementById('user-name'),
        logoutBtn:  document.getElementById('logout-btn'),
      };
      function loadJson(key, def) { try { return JSON.parse(localStorage.getItem(key) || 'null') || def; } catch (e) { return def; } }
      function saveJson(key, val) { localStorage.setItem(key, JSON.stringify(val)); }
      function parseRoute() { const m = location.pathname.match(/\/thread\/([^/]+)/); return m ? m[1] : ''; }
      function toIso(sec) { return new Date(sec * 1000).toISOString(); }
      function projectName(cwd) { const p = (cwd || '').split('/').filter(Boolean); return p[p.length - 1] || cwd || 'unknown'; }
      function formatTime(iso) { if (!iso) return ''; const d = new Date(iso); const now = new Date(); const diff = Math.floor((now - d) / 1000); if (diff < 60) return 'now'; if (diff < 3600) return Math.floor(diff / 60) + 'm'; if (diff < 86400) return Math.floor(diff / 3600) + 'h'; return Math.floor(diff / 86400) + 'd'; }
      function escapeHtml(str) { return String(str).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
      async function rpc(method, params) {
        const res = await fetch('/claude-api/rpc', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ method, params: params != null ? params : null }) });
        const json = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(json.error || `RPC ${method} failed: ${res.status}`);
        if (!json || typeof json !== 'object' || !('result' in json)) throw new Error(`RPC ${method} returned malformed envelope`);
        return json.result;
      }

      async function api(method, path, body) {
        const res = await fetch('/api/claude' + path, { method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
        const json = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(json.error || `API ${method} ${path} failed: ${res.status}`);
        return json;
      }
      function normalizeThreadGroups(payload) {
        const data = (payload && payload.data) || [];
        const existingInProgress = new Set();
        for (const g of state.projectGroups) {
          for (const t of g.threads) {
            if (t.inProgress) existingInProgress.add(t.id);
          }
        }
        const threads = data.map(t => {
          const name = (t.preview || '').trim() || 'Untitled thread';
          const pn = projectName(t.cwd);
          const statusType = (t.status && t.status.type) || '';
          const hasExplicitStatus = !!(t.status && t.status.type) || t.inProgress !== undefined;
          const explicitInProgress = statusType === 'active' || statusType === 'inProgress' || t.inProgress === true;
          // Default to false if no explicit status and not in existing set
          const inProgress = hasExplicitStatus ? explicitInProgress : (existingInProgress.has(t.id) || false);
          return {
            id: t.id,
            title: name,
            projectName: pn,
            cwd: t.cwd,
            createdAtIso: toIso(t.createdAt),
            updatedAtIso: toIso(t.updatedAt),
            preview: t.preview || '',
            unread: false,
            inProgress: inProgress,
          };
        });
        const grouped = new Map();
        for (const t of threads) {
          if (!grouped.has(t.projectName)) grouped.set(t.projectName, []);
          grouped.get(t.projectName).push(t);
        }
        const groups = [];
        for (const [pn, list] of grouped) {
          list.sort((a, b) => new Date(b.updatedAtIso) - new Date(a.updatedAtIso));
          groups.push({ projectName: pn, threads: list });
        }
        groups.sort((a, b) => new Date((b.threads[0] && b.threads[0].updatedAtIso) || 0) - new Date((a.threads[0] && a.threads[0].updatedAtIso) || 0));
        return groups;
      }
      function normalizeMessages(payload) {
        const thread = (payload && payload.thread) || {};
        const turns = thread.turns || [];
        const msgs = [];
        for (const turn of turns) {
          const items = turn.items || [];
          for (const item of items) {
            if (item.type === 'agentMessage') {
              const prev = msgs[msgs.length - 1];
              if (!prev || prev.role !== 'thinking') {
                msgs.push({ id: item.id + '_think', role: 'thinking', text: '（模型未返回思考内容）', messageType: 'thinkingMessage' });
              }
              msgs.push({ id: item.id, role: 'assistant', text: item.text || '', messageType: item.type });
            } else if (item.type === 'thinkingMessage') {
              const text = item.text && item.text.trim() ? item.text : '（模型未返回思考内容）';
              msgs.push({ id: item.id, role: 'thinking', text, messageType: item.type });
            } else if (item.type === 'userMessage') {
              const content = Array.isArray(item.content) ? item.content : [];
              const textChunks = [], images = [];
              for (const blk of content) {
                if (blk.type === 'text' && typeof blk.text === 'string') textChunks.push(blk.text);
                if (blk.type === 'image' && typeof blk.url === 'string') images.push(blk.url);
              }
              const text = textChunks.join('\n');
              // Skip user messages that have no visible text and no images
              // (e.g. tool_result-only messages from the SDK).
              if (!text.trim().length && !images.length) continue;
              msgs.push({ id: item.id, role: 'user', text, images, messageType: item.type });
            }
          }
        }
        return msgs;
      }
      function setConnectionStatus(connected) {
        state.sseConnected = connected;
        const text = connected ? '已连通' : '已断开';
        const cls = 'connection-status ' + (connected ? 'connected' : 'disconnected');
        document.querySelectorAll('.connection-status').forEach(el => {
          el.textContent = text;
          el.className = cls;
        });
      }
      function setThreadInProgress(threadId, val) {
        for (const g of state.projectGroups) {
          const t = g.threads.find(x => x.id === threadId);
          if (t) { t.inProgress = val; break; }
        }
      }
      function updateThreadSendButton() {
        if (!state.selectedThreadId) return;
        const allThreads = state.projectGroups.flatMap(g => g.threads);
        const thread = allThreads.find(t => t.id === state.selectedThreadId);
        const isProcessing = !!thread && thread.inProgress;
        if (isProcessing) {
          els.threadSend.textContent = '停止';
          els.threadSend.classList.remove('btn-primary');
          els.threadSend.classList.add('btn-danger');
          els.threadSend.disabled = false;
        } else {
          els.threadSend.textContent = '发送';
          els.threadSend.classList.remove('btn-danger');
          els.threadSend.classList.add('btn-primary');
          els.threadSend.disabled = false;
        }
      }
      function delay(ms) { return new Promise(r => setTimeout(r, ms)); }
      async function loadCurrentUser() {
        try {
          const res = await fetch('/auth/me');
          if (res.status === 401) { window.location.href = '/login'; return; }
          const data = await res.json().catch(() => ({}));
          const name = data.username || '';
          if (els.userName)   els.userName.textContent = name;
          if (els.userAvatar) els.userAvatar.textContent = name ? name[0].toUpperCase() : '?';
        } catch (e) { console.error('loadCurrentUser failed', e); }
      }
      async function doLogout() {
        try { await fetch('/auth/logout', { method: 'POST' }); } catch (e) {}
        window.location.href = '/login';
      }
      async function ensureThreadResumed(threadId) {
        if (!threadId || state.resumedThreadIds.has(threadId)) return;
        // For claude-agent-sdk, resume is handled automatically via sessionId in options.
        // The SDK persists sessions and resumes them when the same sessionId is provided.
        // No explicit resume call needed from frontend.
        state.resumedThreadIds.add(threadId);
      }
      function buildSessionOptions() {
        // Build options for new session creation.
        const options = {};
        options.permissionMode = 'bypassPermissions';
        options.maxTurns = 50;
        options.persistSession = true;
        return options;
      }
      function buildMessageOptions() {
        // Build per-message options that can override session defaults
        const options = {};
        return options;
      }
