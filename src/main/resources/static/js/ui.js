      function updateLiveOverlay() {
        if (!state.selectedThreadId) {
          state.liveOverlay = null;
          updateThreadSendButton();
          return;
        }
        const allThreads = state.projectGroups.flatMap(g => g.threads);
        const thread = allThreads.find(t => t.id === state.selectedThreadId);
        if (thread && thread.inProgress) {
          state.liveOverlay = { activityLabel: '思考中…', reasoningText: '', errorText: '' };
        } else {
          state.liveOverlay = null;
        }
        // Removed renderConversation() — the conversation list no longer renders
        // the live overlay inline, so re-rendering here only causes DOM churn
        // that can make messages flicker during streaming.
        updateThreadSendButton();
      }
      async function refreshThreads() {
        if (state.isLoadingThreads) return;
        state.isLoadingThreads = true;
        try {
          const result = await api('GET', '');
          state.projectGroups = normalizeThreadGroups(result);
          state.knownThreadIds = new Set(state.projectGroups.flatMap(g => g.threads.map(t => t.id)));
          renderSidebar();
          updateLiveOverlay();

          // If selected thread no longer exists, go home
          if (state.selectedThreadId && !state.knownThreadIds.has(state.selectedThreadId) && !state.recentlyCreatedThreadIds.has(state.selectedThreadId)) {
            selectThread('');
          }
        } catch (e) {
          console.error('refreshThreads failed', e);
        } finally {
          state.isLoadingThreads = false;
          renderSidebar();
        }
      }
      async function loadMessages(threadId) {
        if (!threadId) return;

        // Guard: don't reload while streaming with existing messages — this would
        // replace the live message state and cause the just-sent user message to
        // flicker or disappear. The complete/error event handler will call
        // loadMessages() after setThreadInProgress() clears the inProgress flag.
        const allThreads = state.projectGroups.flatMap(g => g.threads);
        const thread = allThreads.find(t => t.id === threadId);
        if (thread && thread.inProgress && state.messages.length > 0) {
          return;
        }

        await ensureThreadResumed(threadId);
        state.isLoadingMessages = true;
        renderConversation();
        try {
          const result = await api('GET', '/' + encodeURIComponent(threadId));
          state.messages = normalizeMessages(result);
          const threadData = result && result.thread;
          const turns = threadData && threadData.turns ? threadData.turns : [];
          const hasInProgressTurn = turns.some(turn => turn.status === 'inProgress' || turn.status === 'active');
          if (hasInProgressTurn) {
            setThreadInProgress(threadId, true);
          }
        } catch (e) {
          console.error('loadMessages failed', e);
          // Don't clear messages on error — preserve whatever is currently shown.
        } finally {
          state.isLoadingMessages = false;
          renderConversation();
          updateLiveOverlay();
          updateThreadSendButton();
        }
      }
      async function fetchPendingRequests() {
        try {
          const res = await fetch('/claude-api/server-requests/pending');
          const json = await res.json().catch(() => ({}));
          const data = Array.isArray(json.data) ? json.data : [];
          // Merge server-side pending requests with local ones.
          // Server data is authoritative for overlapping IDs.
          // Keep local requests that are not on the server (e.g., permission_request UUIDs).
          const serverIds = new Set(data.map(r => r.id));
          const localOnly = state.pendingRequests.filter(r => !serverIds.has(r.id));
          state.pendingRequests = [...localOnly, ...data];
          renderConversation();
        } catch (e) {
          console.error('fetchPendingRequests failed', e);
        }
      }
      function removePendingRequest(id) {
        state.pendingRequests = state.pendingRequests.filter(r => r.id !== id);
        renderConversation();
      }
      function clearAutoAllowTimer() {
        if (state.autoAllowTimer) { clearInterval(state.autoAllowTimer); state.autoAllowTimer = null; }
        state.autoAllowRequestId = null;
        state.autoAllowSeconds = 10;
      }
      function updateAutoAllowDisplay(seconds) {
        const span = document.querySelector('.auto-allow-timer');
        if (span) span.textContent = String(seconds);
      }
      function startAutoAllowTimer(id, method) {
        clearAutoAllowTimer();
        state.autoAllowRequestId = id;
        state.autoAllowSeconds = 10;
        updateAutoAllowDisplay(10);
        state.autoAllowTimer = setInterval(() => {
          state.autoAllowSeconds--;
          updateAutoAllowDisplay(state.autoAllowSeconds);
          if (state.autoAllowSeconds <= 0) {
            clearAutoAllowTimer();
            if (method === 'approval') respondApproval(id, 'accept');
            else if (method === 'tool') respondToolAllow(id);
            else if (method === 'empty') respondEmpty(id);
          }
        }, 1000);
      }
      function renderSidebar() {
        const tree = els.threadTree;
        if (state.isLoadingThreads && state.projectGroups.length === 0) {
          tree.innerHTML = '<div class="conversation-loading">Loading threads…</div>'; return;
        }

        const search = state.sidebarSearch.trim().toLowerCase();
        const currentCwd = '/home/ubuntu';
        let threads = [];
        for (const g of state.projectGroups) {
          for (const t of g.threads) {
            if (t.cwd === currentCwd) threads.push(t);
          }
        }
        threads.sort((a, b) => new Date(b.updatedAtIso) - new Date(a.updatedAtIso));
        if (search) threads = threads.filter(t => t.title.toLowerCase().includes(search));
        threads = threads.slice(0, 30);

        let html = '';
        html += '<div class="thread-tree-header"><span>最近对话</span><span class="connection-status ' + (state.sseConnected ? 'connected' : 'disconnected') + '">' + (state.sseConnected ? '已连通' : '已断开') + '</span></div>';

        if (threads.length === 0) {
          html += '<div class="project-empty">No threads</div>';
          tree.innerHTML = html; return;
        }

        html += '<ul class="thread-list">';
        for (const t of threads) html += renderThreadRow(t);
        html += '</ul>';
        tree.innerHTML = html;
      }

      function renderThreadRow(t) {
        const active = t.id === state.selectedThreadId;
        const stateAttr = t.unread ? 'unread' : (t.inProgress ? 'inProgress' : '');
        let html = '<li class="thread-row" data-thread-id="' + escapeHtml(t.id) + '" data-action="select-thread" data-active="' + active + '">';
        html += '<div class="thread-left">';
        if (t.unread || t.inProgress) html += '<span class="thread-status" data-state="' + stateAttr + '"></span>';
        html += '</div>';
        html += '<span class="thread-title">' + escapeHtml(t.title) + '</span>';
        html += '<span class="thread-time">' + formatTime(t.updatedAtIso) + '</span>';
        html += '<div class="thread-actions">';
        html += '<button class="icon-btn btn-sm" data-action="archive-thread" data-thread-id="' + escapeHtml(t.id) + '" title="Archive">🗑</button>';
        html += '</div>';
        html += '</li>';
        return html;
      }
      function selectThread(threadId) {
        state.selectedThreadId = threadId || '';
        if (threadId) localStorage.setItem(LS.selectedThread, threadId); else localStorage.removeItem(LS.selectedThread);
        if (threadId) {
          history.replaceState(null, '', '/thread/' + encodeURIComponent(threadId));
        } else {
          history.replaceState(null, '', '/');
        }
        updateView();
        if (threadId) {
          loadMessages(threadId);
          fetchPendingRequests();
        } else {
          state.messages = []; state.pendingRequests = []; state.liveOverlay = null; renderConversation();
        }
        updateThreadSendButton();
      }
      function updateView() {
        const isHome = !state.selectedThreadId;
        els.homeView.style.display = isHome ? 'flex' : 'none';
        els.threadView.style.display = isHome ? 'none' : 'flex';
        els.toggleSidebar.style.display = state.sidebarCollapsed ? 'none' : 'inline-flex';
        els.bodyToggleSidebar.style.display = state.sidebarCollapsed ? 'inline-flex' : 'none';
        renderSidebar();
      }
      function renderConversation() {
        const list = els.conversationList;
        const threadId = state.selectedThreadId;
        if (!threadId) { list.innerHTML = ''; return; }
        if (state.isLoadingMessages && state.messages.length === 0) {
          return;
        }
        if (state.messages.length === 0 && state.pendingRequests.length === 0) {
          return;
        }

        let html = '';
        const requests = state.pendingRequests.filter(r => r.threadId === threadId || !r.threadId);
        if (requests.length > 0) {
          html += '<div class="request-card-overlay"></div>';
          html += renderRequestCard(requests[0]);
        }
        for (const m of state.messages) {
          if (m.messageType === 'worked') {
            html += '<li class="conversation-item"><div class="worked-separator">' + escapeHtml(m.text) + '</div></li>'; continue;
          }
          if (m.role === 'thinking') {
            const isStreaming = !!(m.streaming);
            const expandedClass = isStreaming ? ' expanded' : '';
            html += '<li class="conversation-item">';
            html += '<div class="thinking-card' + expandedClass + '">';
            html += '<div class="thinking-header">思考过程</div>';
            html += '<div class="thinking-body">' + escapeHtml(m.text) + '</div>';
            html += '</div></li>';
            continue;
          }
          // Skip empty messages (e.g. permission/tool responses with no visible content).
          const hasText = !!(m.text && m.text.trim().length);
          const hasImages = !!(m.images && m.images.length);
          if (!hasText && !hasImages) continue;
          html += '<li class="conversation-item"><div class="message-row ' + m.role + '">';
          html += '<div class="message-stack">';
          if (m.images && m.images.length) {
            html += '<div class="message-images">';
            for (const url of m.images) html += '<img src="' + escapeHtml(url) + '" data-action="open-image">';
            html += '</div>';
          }
          if (m.role === 'assistant') {
            html += '<div class="message-card ' + m.role + ' markdown-body">' + marked.parse(m.text || '') + '</div>';
          } else {
            html += '<div class="message-card ' + m.role + '">' + escapeHtml(m.text) + '</div>';
          }
          html += '</div></div></li>';
        }
        // live overlay removed per request
        const wasNearBottom = list.scrollHeight - list.scrollTop <= list.clientHeight + 40;
        list.innerHTML = html;
        if (wasNearBottom) list.scrollTop = list.scrollHeight;
        // Auto-allow countdown: start when a new pending request appears
        const pending = requests[0];
        if (pending) {
          const autoMethod = (pending.method === 'item/commandExecution/requestApproval' || pending.method === 'item/fileChange/requestApproval') ? 'approval'
            : (pending.method === 'item/tool/call') ? 'tool'
            : (pending.method === 'item/tool/requestUserInput') ? null : 'empty';
          if (autoMethod && state.autoAllowRequestId !== pending.id) {
            startAutoAllowTimer(pending.id, autoMethod);
          } else if (autoMethod && state.autoAllowTimer) {
            // Re-render may reset the span to initial value; sync it immediately
            updateAutoAllowDisplay(state.autoAllowSeconds);
          }
        } else {
          clearAutoAllowTimer();
        }
      }
      function getRequestDisplayInfo(r) {
        const m = r.method;
        const p = r.params || {};
        if (m === 'item/commandExecution/requestApproval') {
          return { icon: '⚡', title: '请求执行命令', desc: 'Claude 请求在终端执行以下命令' };
        }
        if (m === 'item/fileChange/requestApproval') {
          return { icon: '📝', title: '请求修改文件', desc: 'Claude 请求对文件进行以下更改' };
        }
        if (m === 'item/tool/requestUserInput') {
          return { icon: '❓', title: '需要您的输入', desc: 'Claude 需要您回答以下问题才能继续' };
        }
        if (m === 'item/tool/call') {
          const toolName = p.toolName || p.tool || '';
          if (toolName) {
            return { icon: '🔧', title: '请求调用工具：' + toolName, desc: 'Claude 请求调用以下工具' };
          }
          return { icon: '🔧', title: '请求调用工具', desc: 'Claude 请求调用外部工具' };
        }
        return { icon: '📋', title: '服务器请求', desc: '收到来自服务器的请求' };
      }
      function renderRequestCard(r) {
        const info = getRequestDisplayInfo(r);
        let html = '<div class="request-card">';
        html += '<div class="request-title"><span class="request-title-icon">' + info.icon + '</span>' + escapeHtml(info.title) + '</div>';
        html += '<div class="request-meta">' + new Date(r.receivedAtIso).toLocaleString() + '</div>';
        if (info.desc) html += '<div class="request-reason" style="color:#64748b;font-size:.85rem;margin-bottom:.5rem;">' + escapeHtml(info.desc) + '</div>';
        const reason = readRequestReason(r);
        if (reason) html += '<div class="request-reason">' + escapeHtml(reason) + '</div>';
        const m = r.method;
        if (m === 'item/commandExecution/requestApproval' || m === 'item/fileChange/requestApproval') {
          html += '<div class="request-actions">';
          html += '<button class="btn btn-primary btn-sm" data-action="respond-approval" data-id="' + r.id + '" data-value="accept" data-auto-allow="true" data-auto-method="approval">✅ 允许 <span class="auto-allow-timer">10</span></button>';
          html += '<button class="btn btn-sm" data-action="respond-approval" data-id="' + r.id + '" data-value="decline">❌ 拒绝</button>';
          html += '<button class="btn btn-sm" data-action="respond-approval" data-id="' + r.id + '" data-value="cancel">🚫 取消</button>';
          html += '</div>';
        } else if (m === 'item/tool/requestUserInput') {
          const questions = readToolQuestions(r);
          html += '<div class="request-user-input">';
          for (const q of questions) {
            html += '<div class="request-question">';
            html += '<div class="request-question-title">' + escapeHtml(q.header || q.question) + '</div>';
            if (q.header && q.question) html += '<div class="request-question-text">' + escapeHtml(q.question) + '</div>';
            html += '<select class="request-select" data-action="question-answer" data-req="' + r.id + '" data-qid="' + escapeHtml(q.id) + '">';
            for (const opt of q.options) html += '<option value="' + escapeHtml(opt) + '">' + escapeHtml(opt) + '</option>';
            html += '</select>';
            if (q.isOther) html += '<input type="text" class="request-input" data-action="question-other" data-req="' + r.id + '" data-qid="' + escapeHtml(q.id) + '" placeholder="其他回答">';
            html += '</div>';
          }
          html += '<button class="btn btn-primary btn-sm" data-action="respond-tool-input" data-id="' + r.id + '">提交回答</button>';
          html += '</div>';
        } else if (m === 'item/tool/call') {
          html += '<div class="request-actions">';
          html += '<button class="btn btn-primary btn-sm" data-action="respond-tool-allow" data-id="' + r.id + '" data-auto-allow="true" data-auto-method="tool">✅ 允许 <span class="auto-allow-timer">10</span></button>';
          html += '<button class="btn btn-sm" data-action="respond-tool-decline" data-id="' + r.id + '">❌ 拒绝</button>';
          html += '</div>';
        } else {
          html += '<div class="request-actions">';
          html += '<button class="btn btn-primary btn-sm" data-action="respond-empty" data-id="' + r.id + '" data-auto-allow="true" data-auto-method="empty">返回空结果 <span class="auto-allow-timer">10</span></button>';
          html += '<button class="btn btn-sm" data-action="respond-reject" data-id="' + r.id + '">拒绝请求</button>';
          html += '</div>';
        }
        html += '</div>';
        return html;
      }
      function readRequestReason(r) {
        const p = r.params || {};
        if (typeof p.reason === 'string') return p.reason;
        if (typeof p.message === 'string') return p.message;
        if (p.command) return typeof p.command === 'string' ? p.command : JSON.stringify(p.command);
        // For tool calls (permission_request), display the tool input details
        if (p.input) {
          if (typeof p.input === 'string') return p.input;
          if (p.input.command) return typeof p.input.command === 'string' ? p.input.command : JSON.stringify(p.input.command);
          // Format Bash tool input nicely
          if (p.input.description && p.input.command) {
            return p.input.description + '\n$ ' + p.input.command;
          }
          // Generic tool input display
          const inputStr = JSON.stringify(p.input, null, 2);
          if (inputStr.length > 300) return inputStr.substring(0, 300) + '...';
          return inputStr;
        }
        return '';
      }
      function readToolQuestions(r) {
        const p = r.params || {};
        const qs = p.questions || p.inputs || [];
        const out = [];
        if (Array.isArray(qs)) {
          for (let i = 0; i < qs.length; i++) {
            const q = qs[i];
            out.push({
              id: String(q.id || i),
              header: q.header || q.title || '',
              question: q.question || q.text || '',
              options: Array.isArray(q.options) ? q.options : ['yes', 'no'],
              isOther: q.type === 'other' || q.allowOther || false,
            });
          }
        }
        return out;
      }
      function setupTextareaAutoResize(textarea) {
        if (!textarea) return;
        // Initialize height immediately to prevent jump on first input
        textarea.style.height = 'auto';
        const initHeight = Math.min(textarea.scrollHeight, 200);
        textarea.style.height = initHeight + 'px';
        textarea.addEventListener('input', () => {
          textarea.style.height = 'auto';
          const newHeight = Math.min(textarea.scrollHeight, 200);
          textarea.style.height = newHeight + 'px';
        });
      }

      function resetTextareaHeight(textarea) {
        if (!textarea) return;
        textarea.style.height = 'auto';
        const newHeight = Math.min(textarea.scrollHeight, 200);
        textarea.style.height = newHeight + 'px';
      }
      function setSidebarCollapsed(v) {
        state.sidebarCollapsed = !!v;
        localStorage.setItem(LS.sidebarCollapsed, state.sidebarCollapsed ? '1' : '0');
        els.sidebar.classList.toggle('collapsed', state.sidebarCollapsed);
        updateView();
      }
      // Removed: toggleProjectCollapse, togglePin, startThreadInProject, showProjectMenu
      function openImageModal(src) { els.imageModalImg.src = src; els.imageModal.classList.add('active'); }
      function closeImageModal() { els.imageModal.classList.remove('active'); els.imageModalImg.src = ''; }
      function setupEvents() {
        setupTextareaAutoResize(els.homeComposer);
        setupTextareaAutoResize(els.threadComposer);
        els.toggleSidebar.addEventListener('click', () => setSidebarCollapsed(!state.sidebarCollapsed));
        els.bodyToggleSidebar.addEventListener('click', () => setSidebarCollapsed(!state.sidebarCollapsed));
        els.newThreadBtn.addEventListener('click', () => selectThread(''));
        els.refreshBtn.addEventListener('click', () => { refreshThreads(); if (state.selectedThreadId) { loadMessages(state.selectedThreadId); fetchPendingRequests(); } });
        els.threadSearch.addEventListener('input', e => { state.sidebarSearch = e.target.value; renderSidebar(); });
        els.homeSend.addEventListener('click', sendHomeMessage);
        els.homeComposer.addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendHomeMessage(); } });
        els.threadSend.addEventListener('click', handleThreadSendClick);
        els.threadComposer.addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleThreadSendClick(); } });
        els.imageModalClose.addEventListener('click', closeImageModal);
        els.imageModal.addEventListener('click', e => { if (e.target === els.imageModal) closeImageModal(); });
        if (els.logoutBtn) els.logoutBtn.addEventListener('click', doLogout);

        document.addEventListener('click', e => {
          const menu = document.querySelector('.context-menu');
          if (menu && !menu.contains(e.target)) menu.remove();
        });

        document.addEventListener('keydown', e => {
          if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'b') { e.preventDefault(); setSidebarCollapsed(!state.sidebarCollapsed); }
          if (e.key === 'Escape') closeImageModal();
        });

        els.threadTree.addEventListener('click', e => {
          const btn = e.target.closest('button');
          if (!btn) {
            const row = e.target.closest('.thread-row');
            if (row) { const tid = row.getAttribute('data-thread-id'); if (tid) selectThread(tid); }
            return;
          }
          const action = btn.getAttribute('data-action');
          if (action === 'select-thread') { const tid = btn.getAttribute('data-thread-id'); if (tid) selectThread(tid); }
          if (action === 'archive-thread') { const tid = btn.getAttribute('data-thread-id'); if (tid) archiveThread(tid); }
        });

        els.conversationList.addEventListener('click', e => {
          const btn = e.target.closest('button');
          if (!btn) {
            const thinkingHeader = e.target.closest('.thinking-header');
            if (thinkingHeader) {
              thinkingHeader.closest('.thinking-card').classList.toggle('expanded');
              return;
            }
            const img = e.target.closest('img[data-action="open-image"]');
            if (img) openImageModal(img.src);
            return;
          }
          const action = btn.getAttribute('data-action');
          const rawId = btn.getAttribute('data-id');
          const id = Number(rawId) || rawId;
          clearAutoAllowTimer();
          if (action === 'respond-approval') respondApproval(id, btn.getAttribute('data-value'));
          if (action === 'respond-tool-input') respondToolInput(id);
          if (action === 'respond-tool-allow') respondToolAllow(id);
          if (action === 'respond-tool-decline') respondToolDecline(id);
          if (action === 'respond-tool-fail') respondToolFail(id);
          if (action === 'respond-tool-success') respondToolSuccess(id);
          if (action === 'respond-empty') respondEmpty(id);
          if (action === 'respond-reject') respondReject(id);
        });
      }
