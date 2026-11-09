(function () {
    'use strict';

    const cfg = window.MINIFLOW;
    const wf = cfg.workflow;
    const T = Object.assign({
        unsaved: 'Unsaved changes', saved: 'Saved', saveFailed: 'Could not save',
        testDataInvalid: 'Test data is not valid JSON', running: 'Running…', runFailed: 'Could not run',
        testOk: 'Test succeeded', testFailed: 'Test finished with errors', success: 'Success', error: 'Error',
        idInvalid: 'ID may only contain letters, digits, _ and -.', idTaken: 'This ID is used by another node.',
        paramsInvalid: 'Parameters are not valid JSON:', paramsNotObject: 'Parameters must be a JSON object.',
        errorPrefix: 'ERROR:', active: 'Workflow active', inactive: 'Workflow inactive',
        credAvailable: 'Available credentials:', credNone: 'No saved credentials for this node yet.',
        credManage: 'Manage credentials →', credType: 'Required type:',
        dataFromTest: 'from the last test run', dataFromHistory: 'from the last execution',
        dataFromSample: 'from the test data – run a test to see every step', dataNotRun: 'not run yet',
        dataVars: 'Variables', dataFunctions: 'Functions', dataEmpty: '(empty)',
        dataInput: 'input = output of the previous step',
        dataCopied: 'Copied {0} – click into a parameter value first to insert it directly',
        formAdd: '+ Add', formKeyPh: 'name', formValuePh: 'value or {{ expression }}',
        formExtra: 'More parameters are only visible in the JSON view:', formNone: '(none)',
        formJsonInvalid: 'Not valid JSON yet – kept as text',
        blocks_add: '+ Add block', blocks_header: 'Header', blocks_section: 'Text', blocks_fields: 'Fields',
        blocks_divider: 'Divider', blocks_context: 'Small note', blocks_button: 'Link button', blocks_image: 'Image',
        blocks_fieldLabel: 'Label', blocks_fieldValue: 'Value or {{ expression }}', blocks_addField: '+ Field',
        blocks_text: 'Text (*bold*, _italic_, <url|link>)', blocks_url: 'URL', blocks_alt: 'Description',
        blocks_unsupported: 'These blocks contain types the form cannot edit – use the JSON tab.',
        blocks_preset: 'Use status card template', blocks_up: 'Move up', blocks_down: 'Move down',
        blocks_remove: 'Remove', blocks_empty: 'No blocks – the plain message above is sent.'
    }, cfg.i18n || {});
    const vars = cfg.vars || {};
    const credentials = cfg.credentials || [];
    const base = cfg.base.endsWith('/') ? cfg.base : cfg.base + '/';
    const typeMap = {};
    cfg.nodeTypes.forEach(t => { typeMap[t.type] = t; });

    const $ = id => document.getElementById(id);
    const els = {
        canvas: $('drawflow'), name: $('wf-name'), active: $('wf-active'), status: $('status'),
        save: $('btn-save'), run: $('btn-run'),
        emptyPanel: $('empty-panel'), nodePanel: $('node-panel'),
        npType: $('np-type'), npDesc: $('np-desc'), npId: $('np-id'), npParams: $('np-params'),
        npError: $('np-error'), npApply: $('np-apply'), npDelete: $('np-delete'),
        npOutputWrap: $('np-output-wrap'), npOutput: $('np-output'), npCred: $('np-cred'),
        testData: $('test-data'),
        npPreview: $('np-preview'), npPreviewList: $('np-preview-list'),
        npDataSource: $('np-data-source'), npDataTree: $('np-data-tree'),
        npTabs: $('np-tabs'), npForm: $('np-form'), npJsonWrap: $('np-json-wrap'),
        npRetry: $('np-retry'), npRetryOn: $('np-retry-on'), npRetryTimes: $('np-retry-times'),
        npRetryDelay: $('np-retry-delay'), npRetryBackoff: $('np-retry-backoff'), npOnError: $('np-onerror'),
        runPanel: $('run-panel'), runBadge: $('run-badge'), runSteps: $('run-steps'), runLink: $('run-link')
    };

    const editor = new Drawflow(els.canvas);
    editor.reroute = true;
    editor.start();

    let selectedDfId = null;
    let lastRun = {};
    let runData = null;
    let lastCaret = null;
    let activeTab = 'form';
    try { activeTab = localStorage.getItem('miniflow:paramsTab') || 'form'; } catch (e) {  }
    let formParams = {};
    let lastField = null;
    let dirty = false;
    let loading = true;

    function esc(s) {
        return String(s).replace(/[&<>"']/g, c => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        }[c]));
    }

    function typeInfo(type) {
        return typeMap[type] || { type: type, label: type, description: '', outputs: ['main'], hasInput: true, defaultParams: {} };
    }

    function nodeHtml(t, nodeId) {
        return '<div class="node-head">' + esc(t.label) + '</div>' +
               '<div class="node-title">' + esc(nodeId) + '</div>';
    }

    function nodeEl(dfId) {
        return document.getElementById('node-' + dfId);
    }

    function setStatus(text, kind) {
        els.status.textContent = text || '';
        els.status.className = 'status' + (kind ? ' ' + kind : '');
    }

    function markDirty() {
        if (loading) return;
        dirty = true;
        setStatus(T.unsaved, 'warn');
    }

    function allNodes() {
        return editor.export().drawflow.Home.data;
    }

    function dfIdByNodeId(nodeId) {
        const data = allNodes();
        return Object.keys(data).find(k => data[k].data.nodeId === nodeId);
    }

    function uniqueNodeId(type) {
        const used = new Set(Object.values(allNodes()).map(n => n.data.nodeId));
        let i = 1;
        while (used.has(type + i)) i++;
        return type + i;
    }

    function outputsOf(data) {
        const outs = typeInfo(data.type).outputs;
        return data.onError === 'branch' ? outs.concat(['error']) : outs;
    }

    function labelOutputs(dfId) {
        const outputs = outputsOf(editor.getNodeFromId(dfId).data);
        const outs = nodeEl(dfId).querySelectorAll('.outputs .output');
        outs.forEach((o, i) => {
            if (outputs.length < 2) delete o.dataset.label;
            else o.dataset.label = outputs[i] || '';
        });
    }

    function addNode(type, nodeId, params, x, y, retry, onError) {
        const t = typeInfo(type);
        const data = { nodeId: nodeId, type: type, params: params || {} };
        if (retry) data.retry = retry;
        if (onError === 'branch') data.onError = 'branch';
        const dfId = editor.addNode(type, t.hasInput ? 1 : 0, outputsOf(data).length, x, y,
            'mf-node type-' + type, data, nodeHtml(t, nodeId));
        labelOutputs(dfId);
        return dfId;
    }

    function autoLayout(nodes, connections) {
        const depth = {};
        const queue = [];
        nodes.filter(n => n.type === 'trigger').forEach(n => { depth[n.id] = 0; queue.push(n.id); });
        while (queue.length) {
            const id = queue.shift();
            connections.filter(c => c.from === id).forEach(c => {
                const d = depth[id] + 1;
                if ((depth[c.to] === undefined || depth[c.to] < d) && d <= nodes.length) {
                    depth[c.to] = d;
                    queue.push(c.to);
                }
            });
        }
        const perLevel = {};
        const pos = {};
        nodes.forEach(n => {
            const d = depth[n.id] === undefined ? 0 : depth[n.id];
            const idx = perLevel[d] = (perLevel[d] === undefined ? 0 : perLevel[d] + 1);
            pos[n.id] = { x: 40 + d * 260, y: 80 + idx * 150 };
        });
        return pos;
    }

    function load() {
        const layout = autoLayout(wf.nodes, wf.connections);
        const dfIds = {};
        const defs = {};
        wf.nodes.forEach(n => {
            const x = n.x != null ? n.x : layout[n.id].x;
            const y = n.y != null ? n.y : layout[n.id].y;
            dfIds[n.id] = addNode(n.type, n.id, n.params, x, y, n.retry, n.onError);
            defs[n.id] = n;
        });
        wf.connections.forEach(c => {
            if (!dfIds[c.from] || !dfIds[c.to]) return;
            const idx = outputsOf(defs[c.from]).indexOf(c.branch || 'main');
            if (idx < 0) return;
            editor.addConnection(dfIds[c.from], dfIds[c.to], 'output_' + (idx + 1), 'input_1');
        });

        let saved = null;
        try { saved = localStorage.getItem('miniflow:test:' + wf.id); } catch (e) {  }
        const trigger = wf.nodes.find(n => n.type === 'trigger');
        const sample = trigger && trigger.params && trigger.params.sample;
        els.testData.value = saved || JSON.stringify(sample || {}, null, 2);
        loading = false;
    }

    function toWorkflow() {
        const data = allNodes();
        const nodes = [];
        const connections = [];
        Object.values(data).forEach(df => {
            const node = {
                id: df.data.nodeId,
                type: df.data.type,
                params: df.data.params,
                x: Math.round(df.pos_x),
                y: Math.round(df.pos_y)
            };
            if (df.data.retry) node.retry = df.data.retry;
            if (df.data.onError) node.onError = df.data.onError;
            nodes.push(node);
            const outputs = outputsOf(df.data);
            Object.keys(df.outputs).forEach(outKey => {
                const idx = parseInt(outKey.split('_')[1], 10) - 1;
                const branch = outputs[idx] || 'main';
                df.outputs[outKey].connections.forEach(c => {
                    connections.push({ from: df.data.nodeId, to: data[c.node].data.nodeId, branch: branch });
                });
            });
        });
        return {
            id: wf.id,
            name: els.name.value.trim() || wf.id,
            active: els.active.checked,
            nodes: nodes,
            connections: connections
        };
    }

    async function save() {
        const body = toWorkflow();
        const res = await fetch(base + 'api/workflows/' + encodeURIComponent(wf.id), {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        if (!res.ok) {
            let msg = T.saveFailed + ' (' + res.status + ')';
            try { msg = (await res.json()).error || msg; } catch (e) {  }
            throw new Error(msg);
        }
        dirty = false;
        setStatus(T.saved, 'ok');
    }

    function parseJsonLenient(text) {
        let out = '', inString = false, escaped = false;
        for (const ch of text) {
            if (inString) {
                if (escaped) { escaped = false; }
                else if (ch === '\\') { escaped = true; }
                else if (ch === '"') { inString = false; }
                else if (ch === '\n') { out += '\\n'; continue; }
                else if (ch === '\r') { continue; }
                else if (ch === '\t') { out += '\\t'; continue; }
            } else if (ch === '"') {
                inString = true;
            }
            out += ch;
        }
        return JSON.parse(out);
    }

    async function run() {
        let data;
        try {
            data = parseJsonLenient(els.testData.value || '{}');
        } catch (e) {
            setStatus(T.testDataInvalid, 'err');
            return;
        }
        try { localStorage.setItem('miniflow:test:' + wf.id, els.testData.value); } catch (e) {  }

        els.run.disabled = true;
        try {
            await save();
            setStatus(T.running);
            const res = await fetch(base + 'api/workflows/' + encodeURIComponent(wf.id) + '/run', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            if (!res.ok) throw new Error(T.runFailed + ' (' + res.status + ')');
            showRun(await res.json());
        } catch (e) {
            setStatus(e.message, 'err');
        } finally {
            els.run.disabled = false;
        }
    }

    function showRun(ex) {
        lastRun = {};
        const outputs = {};
        ex.steps.forEach(step => { if (step.output) outputs[step.nodeId] = step.output; });
        runData = { trigger: ex.trigger || {}, nodes: outputs, source: 'test' };
        document.querySelectorAll('.drawflow-node').forEach(n => n.classList.remove('run-ok', 'run-err'));

        els.runSteps.innerHTML = '';
        ex.steps.forEach(step => {
            lastRun[step.nodeId] = step;
            const dfId = dfIdByNodeId(step.nodeId);
            if (dfId) nodeEl(dfId).classList.add(step.status === 'SUCCESS' ? 'run-ok' : 'run-err');

            const li = document.createElement('li');
            li.className = step.status === 'SUCCESS' ? 'ok' : 'err';
            li.innerHTML = '<strong>' + esc(step.nodeId) + '</strong>' +
                (step.branch && step.branch !== 'main' ? ' <span class="tag branch">→ ' + esc(step.branch) + '</span>' : '') +
                (step.attempts > 1 ? ' <span class="tag retry">↻ ' + step.attempts + '</span>' : '') +
                ' <span class="muted">' + step.durationMs + ' ms</span>' +
                (step.error ? '<div class="small err-text">' + esc(step.error) + '</div>' : '');
            if (dfId) li.addEventListener('click', () => showNode(dfId));
            els.runSteps.appendChild(li);
        });

        const ok = ex.status === 'SUCCESS';
        els.runBadge.textContent = ok ? T.success : T.error;
        els.runBadge.className = 'badge ' + (ok ? 'ok' : 'err');
        els.runLink.href = base + 'executions/' + encodeURIComponent(ex.id);
        els.runPanel.hidden = false;
        setStatus(ok ? T.testOk + ' (' + ex.durationMs + ' ms)' : T.testFailed, ok ? 'ok' : 'err');

        if (selectedDfId) showNode(selectedDfId);
    }

    function showNode(dfId) {
        const node = editor.getNodeFromId(dfId);
        if (!node) return;
        selectedDfId = String(dfId);
        const t = typeInfo(node.data.type);
        els.npType.textContent = t.label;
        els.npDesc.textContent = t.description || '';
        els.npId.value = node.data.nodeId;
        els.npParams.value = JSON.stringify(node.data.params || {}, null, 2);
        fillRetry(node.data.type === 'trigger' ? null : node.data.retry, node.data.type !== 'trigger', node.data.onError);
        els.npError.hidden = true;
        showCredentialHint(t);

        const step = lastRun[node.data.nodeId];
        if (step) {
            els.npOutput.textContent = step.error ? T.errorPrefix + ' ' + step.error : JSON.stringify(step.output, null, 2);
            els.npOutputWrap.hidden = false;
        } else {
            els.npOutputWrap.hidden = true;
        }
        els.emptyPanel.hidden = true;
        els.nodePanel.hidden = false;
        lastCaret = null;
        lastField = null;
        const hasForm = fieldsOf(node.data.type).length > 0;
        if (els.npTabs) {
            els.npTabs.hidden = !hasForm;
            if (hasForm) {
                setTab(activeTab === 'json' ? 'json' : 'form', true);
            } else {
                els.npForm.hidden = true;
                els.npJsonWrap.hidden = false;
            }
        }
        renderDataPanel();
        schedulePreview(0);
    }

    function showCredentialHint(t) {
        if (!els.npCred) return;
        const types = t.credentialTypes || [];
        if (!types.length) {
            els.npCred.hidden = true;
            return;
        }
        const matching = credentials.filter(c => types.indexOf(c.type) >= 0);
        const link = '<a href="' + esc(base + 'credentials') + '" target="_blank">' + esc(T.credManage) + '</a>';
        els.npCred.innerHTML = matching.length
            ? esc(T.credAvailable) + ' ' + matching.map(c => '<code>' + esc(c.name) + '</code>').join(', ') + ' · ' + link
            : esc(T.credNone) + ' ' + esc(T.credType) + ' <code>' + esc(types.join(', ')) + '</code> · ' + link;
        els.npCred.hidden = false;
    }

    function hideNode() {
        selectedDfId = null;
        els.nodePanel.hidden = true;
        els.emptyPanel.hidden = false;
    }

    function panelError(msg) {
        els.npError.textContent = msg;
        els.npError.hidden = false;
    }

    function applyNode() {
        if (!selectedDfId) return;
        const node = editor.getNodeFromId(selectedDfId);
        const newId = els.npId.value.trim();
        if (!/^[A-Za-z0-9_-]+$/.test(newId)) {
            panelError(T.idInvalid);
            return;
        }
        const clash = dfIdByNodeId(newId);
        if (clash && clash !== selectedDfId) {
            panelError(T.idTaken);
            return;
        }
        let params;
        try {
            params = parseJsonLenient(els.npParams.value || '{}');
        } catch (e) {
            panelError(T.paramsInvalid + ' ' + e.message);
            return;
        }
        if (params === null || typeof params !== 'object' || Array.isArray(params)) {
            panelError(T.paramsNotObject);
            return;
        }
        const updated = { nodeId: newId, type: node.data.type, params: params };
        const retry = readRetry();
        if (retry) updated.retry = retry;
        const onError = readOnError();
        if (onError) updated.onError = onError;
        const hadErrorOutput = node.data.onError === 'branch';
        editor.updateNodeDataFromId(selectedDfId, updated);
        if (!hadErrorOutput && onError === 'branch') {
            editor.addNodeOutput(selectedDfId);
        } else if (hadErrorOutput && onError !== 'branch') {
            editor.removeNodeOutput(selectedDfId, 'output_' + outputsOf(updated).concat(['error']).length);
        }
        labelOutputs(selectedDfId);
        nodeEl(selectedDfId).querySelector('.node-title').textContent = newId;
        els.npError.hidden = true;
        markDirty();
    }

    function fillRetry(retry, available, onError) {
        if (!els.npRetry) return;
        els.npRetry.hidden = !available;
        if (els.npOnError) els.npOnError.value = onError === 'branch' ? 'branch' : 'stop';
        els.npRetryOn.checked = !!retry;
        els.npRetryTimes.value = retry ? retry.times : 3;
        els.npRetryDelay.value = retry && retry.delayMs != null ? retry.delayMs : 1000;
        els.npRetryBackoff.value = retry && retry.backoff != null ? retry.backoff : 2;
        els.npRetry.open = !!retry || onError === 'branch';
        [els.npRetryTimes, els.npRetryDelay, els.npRetryBackoff].forEach(i => { i.disabled = !retry; });
    }

    function readOnError() {
        if (!els.npOnError || els.npRetry.hidden) return null;
        return els.npOnError.value === 'branch' ? 'branch' : null;
    }

    function readRetry() {
        if (!els.npRetry || els.npRetry.hidden || !els.npRetryOn.checked) return null;
        const num = (input, def, min, max) => {
            const v = Number(input.value);
            return Number.isFinite(v) ? Math.min(max, Math.max(min, v)) : def;
        };
        return {
            times: Math.round(num(els.npRetryTimes, 3, 0, 5)),
            delayMs: Math.round(num(els.npRetryDelay, 1000, 0, 30000)),
            backoff: num(els.npRetryBackoff, 2, 1, 10)
        };
    }

    if (els.npRetry) {
        els.npRetryOn.addEventListener('change', () => {
            [els.npRetryTimes, els.npRetryDelay, els.npRetryBackoff].forEach(i => { i.disabled = !els.npRetryOn.checked; });
            applyNode();
        });
        [els.npRetryTimes, els.npRetryDelay, els.npRetryBackoff].forEach(i => i.addEventListener('change', applyNode));
        if (els.npOnError) els.npOnError.addEventListener('change', applyNode);
    }

    const TRUE_BY_DEFAULT = ['failOnError', 'keepInput', 'declareQueue', 'persistent'];

    function fieldsOf(type) {
        return typeInfo(type).fields || [];
    }

    function formActive() {
        return activeTab === 'form' && els.npForm && !els.npForm.hidden;
    }

    function setTab(tab, fromShowNode) {
        if (tab === 'form') {
            let p;
            try {
                p = parseJsonLenient(els.npParams.value || '{}');
            } catch (e) {
                panelError(T.paramsInvalid + ' ' + e.message);
                return;
            }
            if (!p || typeof p !== 'object' || Array.isArray(p)) {
                panelError(T.paramsNotObject);
                return;
            }
            formParams = p;
            if (!fromShowNode) els.npError.hidden = true;
        }
        activeTab = tab;
        try { localStorage.setItem('miniflow:paramsTab', tab); } catch (e) {  }
        els.npTabs.querySelectorAll('.np-tab').forEach(b => {
            b.classList.toggle('active', b.dataset.tab === tab);
            b.setAttribute('aria-selected', b.dataset.tab === tab ? 'true' : 'false');
        });
        els.npForm.hidden = tab !== 'form';
        els.npJsonWrap.hidden = tab !== 'json';
        lastField = null;
        lastCaret = null;
        if (tab === 'form') renderForm();
    }

    function syncJson() {
        els.npParams.value = JSON.stringify(formParams, null, 2);
        schedulePreview();
        applyNode();
    }

    function setParam(f, value) {
        if (value === undefined || (value === '' && !f.required)) {
            delete formParams[f.name];
        } else {
            formParams[f.name] = value;
        }
        syncJson();
    }

    function currentNodeFields() {
        const node = selectedDfId && editor.getNodeFromId(selectedDfId);
        return node ? fieldsOf(node.data.type) : [];
    }

    function isVisible(f, fields) {
        if (!f.showIf) return true;
        const i = f.showIf.indexOf('=');
        const key = f.showIf.slice(0, i);
        const allowed = f.showIf.slice(i + 1).split(',');
        let cur = formParams[key];
        if (cur === undefined || cur === null || cur === '') {
            const ctrl = fields.find(x => x.name === key);
            cur = ctrl && ctrl.options && ctrl.options.length ? ctrl.options[0] : '';
        }
        return allowed.indexOf(String(cur)) >= 0;
    }

    function textOf(v) {
        if (v === undefined || v === null) return '';
        return typeof v === 'object' ? JSON.stringify(v, null, 2) : String(v);
    }

    function literal(text, original) {
        const t = text.trim();
        if (typeof original === 'string') return text;
        if (/^-?\d+(\.\d+)?$/.test(t)) return Number(t);
        if (t === 'true' || t === 'false') return t === 'true';
        if ((t.startsWith('{') || t.startsWith('[')) && original !== undefined) {
            try { return parseJsonLenient(t); } catch (e) { return text; }
        }
        return text;
    }

    function el(tag, cls, text) {
        const e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text !== undefined) e.textContent = text;
        return e;
    }

    function textControl(f, multiline, mono, rows) {
        const c = multiline ? el('textarea', mono ? 'mono' : '') : el('input');
        if (multiline) c.rows = rows;
        else c.type = 'text';
        c.spellcheck = false;
        if (f.placeholder) c.placeholder = f.placeholder;
        const original = formParams[f.name];
        c.value = textOf(original);
        c.addEventListener('input', () => {
            setParam(f, f.kind === 'number' ? literal(c.value, typeof original === 'string' ? undefined : original) : c.value);
        });
        return c;
    }

    function jsonControl(f) {
        const c = el('textarea', 'mono');
        c.rows = 4;
        c.spellcheck = false;
        if (f.placeholder) c.placeholder = f.placeholder;
        c.value = textOf(formParams[f.name]);
        const warn = el('small', 'ff-warn', T.formJsonInvalid);
        warn.hidden = true;
        c.addEventListener('input', () => {
            const t = c.value.trim();
            warn.hidden = true;
            if (!t) { setParam(f, undefined); return; }
            if (t.startsWith('{') || t.startsWith('[')) {
                try { setParam(f, parseJsonLenient(t)); return; } catch (e) { warn.hidden = false; }
            }
            setParam(f, c.value);
        });
        const wrap = el('div');
        wrap.appendChild(c);
        wrap.appendChild(warn);
        return wrap;
    }

    function selectControl(f, options, t) {
        const c = el('select');
        const cur = formParams[f.name];
        const opts = options.slice();
        if (cur !== undefined && cur !== null && cur !== '' && opts.indexOf(String(cur)) < 0) opts.unshift(String(cur));
        if (!f.required || cur === undefined || cur === '') {
            const o = el('option', '', f.kind === 'credential' && !f.required ? '—' : (f.required ? '' : '—'));
            o.value = '';
            c.appendChild(o);
        }
        opts.forEach(v => {
            const o = el('option', '', v);
            o.value = v;
            c.appendChild(o);
        });
        c.value = cur === undefined || cur === null ? (f.required && f.kind === 'select' && opts.length ? opts[0] : '') : String(cur);
        if (f.required && f.kind === 'select' && (cur === undefined || cur === '') && opts.length) {
            formParams[f.name] = opts[0];
        }
        c.addEventListener('change', () => {
            setParam(f, c.value);
            if (currentNodeFields().some(x => x.showIf && x.showIf.split('=')[0] === f.name)) renderForm();
        });
        return c;
    }

    function keyValueControl(f) {
        const wrap = el('div', 'kv');
        const original = formParams[f.name] && typeof formParams[f.name] === 'object' ? formParams[f.name] : {};
        const rowsEl = el('div', 'kv-rows');
        const collect = () => {
            const obj = {};
            rowsEl.querySelectorAll('.kv-row').forEach(row => {
                const k = row.querySelector('.kv-k').value.trim();
                if (!k) return;
                obj[k] = literal(row.querySelector('.kv-v').value, original[k]);
            });
            setParam(f, Object.keys(obj).length || formParams[f.name] !== undefined ? obj : undefined);
        };
        const addRow = (k, v) => {
            const row = el('div', 'kv-row');
            const ki = el('input', 'kv-k'); ki.type = 'text'; ki.placeholder = T.formKeyPh; ki.value = k;
            const vi = el('input', 'kv-v'); vi.type = 'text'; vi.placeholder = T.formValuePh; vi.value = v;
            const del = el('button', 'kv-del', '×'); del.type = 'button'; del.title = '×';
            del.addEventListener('click', () => { row.remove(); collect(); });
            [ki, vi].forEach(i => i.addEventListener('input', collect));
            row.appendChild(ki); row.appendChild(vi); row.appendChild(del);
            rowsEl.appendChild(row);
            return ki;
        };
        Object.keys(original).forEach(k => addRow(k, textOf(original[k])));
        const add = el('button', 'btn sm kv-add', T.formAdd);
        add.type = 'button';
        add.addEventListener('click', () => addRow('', '').focus());
        wrap.appendChild(rowsEl);
        wrap.appendChild(add);
        return wrap;
    }

    function listControl(f) {
        const c = el('textarea', 'mono');
        c.rows = 3;
        c.spellcheck = false;
        const v = formParams[f.name];
        c.value = Array.isArray(v) ? v.map(textOf).join('\n') : '';
        c.addEventListener('input', () => {
            const items = c.value.split('\n').map(x => x.trim()).filter(Boolean);
            setParam(f, items.length ? items : undefined);
        });
        return c;
    }

    function blockToItem(b) {
        if (!b || typeof b !== 'object') return null;
        const txt = o => (o && typeof o === 'object' ? (o.text || '') : '');
        switch (b.type) {
            case 'header': return { kind: 'header', text: txt(b.text) };
            case 'divider': return { kind: 'divider' };
            case 'context': {
                const els = b.elements || [];
                if (els.length !== 1 || !els[0] || els[0].type === 'image') return null;
                return { kind: 'context', text: els[0].text || '' };
            }
            case 'section': {
                const fields = (b.fields || []).map(x => {
                    const m = /^\*([^*\n]+)\*\n([\s\S]*)$/.exec(txt(x));
                    return m ? { label: m[1], value: m[2] } : { label: '', value: txt(x) };
                });
                if (b.accessory) return null;
                return { kind: 'section', text: txt(b.text), fields: fields };
            }
            case 'actions': {
                const els = b.elements || [];
                if (els.length !== 1 || els[0].type !== 'button' || !els[0].url) return null;
                return { kind: 'button', text: txt(els[0].text), url: els[0].url };
            }
            case 'image': return { kind: 'image', url: b.image_url || '', alt: b.alt_text || '' };
            default: return null;
        }
    }

    function itemToBlock(it) {
        switch (it.kind) {
            case 'header': return { type: 'header', text: { type: 'plain_text', text: it.text || ' ', emoji: true } };
            case 'divider': return { type: 'divider' };
            case 'context': return { type: 'context', elements: [{ type: 'mrkdwn', text: it.text || ' ' }] };
            case 'section': {
                const b = { type: 'section' };
                if (it.text) b.text = { type: 'mrkdwn', text: it.text };
                const fields = (it.fields || []).filter(x => x.label || x.value)
                    .map(x => ({ type: 'mrkdwn', text: (x.label ? '*' + x.label + '*\n' : '') + (x.value || ' ') }));
                if (fields.length) b.fields = fields;
                if (!b.text && !b.fields) b.text = { type: 'mrkdwn', text: ' ' };
                return b;
            }
            case 'button': return { type: 'actions', elements: [{ type: 'button', text: { type: 'plain_text', text: it.text || 'Open', emoji: true }, url: it.url || '' }] };
            case 'image': return { type: 'image', image_url: it.url || '', alt_text: it.alt || 'image' };
            default: return null;
        }
    }

    function blocksControl(f) {
        const wrap = el('div', 'bk');
        const current = formParams[f.name];
        let items = [];
        if (Array.isArray(current)) {
            const mapped = current.map(blockToItem);
            if (mapped.some(m => m === null)) {
                wrap.appendChild(el('small', 'ff-warn', T.blocks_unsupported));
                wrap.appendChild(jsonControl(f));
                return wrap;
            }
            items = mapped;
        } else if (current !== undefined && current !== null && current !== '') {
            return jsonControl(f);
        }

        const list = el('div', 'bk-list');
        const save = () => setParam(f, items.length ? items.map(itemToBlock) : undefined);
        const input = (value, ph, onInput, multiline) => {
            const c = multiline ? el('textarea') : el('input');
            if (multiline) c.rows = 2; else c.type = 'text';
            c.value = value || '';
            c.placeholder = ph;
            c.spellcheck = false;
            c.addEventListener('input', () => { onInput(c.value); save(); });
            return c;
        };
        const iconBtn = (label, title, fn) => {
            const b = el('button', 'bk-icon', label);
            b.type = 'button';
            b.title = title;
            b.addEventListener('click', fn);
            return b;
        };

        function draw() {
            list.innerHTML = '';
            if (!items.length) list.appendChild(el('div', 'bk-empty', T.blocks_empty));
            items.forEach((it, i) => {
                const card = el('div', 'bk-card bk-' + it.kind);
                const head = el('div', 'bk-head');
                head.appendChild(el('strong', '', T['blocks_' + it.kind] || it.kind));
                head.appendChild(el('span', 'spacer'));
                head.appendChild(iconBtn('↑', T.blocks_up, () => { if (i > 0) { items.splice(i - 1, 0, items.splice(i, 1)[0]); save(); draw(); } }));
                head.appendChild(iconBtn('↓', T.blocks_down, () => { if (i < items.length - 1) { items.splice(i + 1, 0, items.splice(i, 1)[0]); save(); draw(); } }));
                head.appendChild(iconBtn('×', T.blocks_remove, () => { items.splice(i, 1); save(); draw(); }));
                card.appendChild(head);
                const body = el('div', 'bk-body');
                if (it.kind === 'header') body.appendChild(input(it.text, T.blocks_header, v => { it.text = v; }));
                if (it.kind === 'context') body.appendChild(input(it.text, T.blocks_text, v => { it.text = v; }));
                if (it.kind === 'section') {
                    body.appendChild(input(it.text, T.blocks_text, v => { it.text = v; }, true));
                    const fl = el('div', 'bk-fields');
                    (it.fields || []).forEach((fd, j) => {
                        const row = el('div', 'bk-field');
                        row.appendChild(input(fd.label, T.blocks_fieldLabel, v => { fd.label = v; }));
                        row.appendChild(input(fd.value, T.blocks_fieldValue, v => { fd.value = v; }));
                        row.appendChild(iconBtn('×', T.blocks_remove, () => { it.fields.splice(j, 1); save(); draw(); }));
                        fl.appendChild(row);
                    });
                    const addF = el('button', 'btn sm', T.blocks_addField);
                    addF.type = 'button';
                    addF.addEventListener('click', () => { (it.fields = it.fields || []).push({ label: '', value: '' }); save(); draw(); });
                    fl.appendChild(addF);
                    body.appendChild(fl);
                }
                if (it.kind === 'button') {
                    body.appendChild(input(it.text, T.blocks_button, v => { it.text = v; }));
                    body.appendChild(input(it.url, T.blocks_url, v => { it.url = v; }));
                }
                if (it.kind === 'image') {
                    body.appendChild(input(it.url, T.blocks_url, v => { it.url = v; }));
                    body.appendChild(input(it.alt, T.blocks_alt, v => { it.alt = v; }));
                }
                if (body.children.length) card.appendChild(body);
                list.appendChild(card);
            });
        }

        const bar = el('div', 'bk-bar');
        const sel = el('select', 'bk-add');
        const first = el('option', '', T.blocks_add);
        first.value = '';
        sel.appendChild(first);
        ['header', 'section', 'divider', 'context', 'button', 'image'].forEach(k => {
            const o = el('option', '', T['blocks_' + k]);
            o.value = k;
            sel.appendChild(o);
        });
        sel.addEventListener('change', () => {
            if (!sel.value) return;
            items.push(sel.value === 'section' ? { kind: 'section', text: '', fields: [] } : { kind: sel.value });
            sel.value = '';
            save();
            draw();
        });
        const preset = el('button', 'btn sm ghost', T.blocks_preset);
        preset.type = 'button';
        preset.addEventListener('click', () => {
            items = [
                { kind: 'header', text: '{{ input.ok ? \'🟢\' : \'🔴\' }} Status' },
                { kind: 'section', text: '', fields: [
                    { label: 'Status', value: '{{ input.status }}' },
                    { label: 'Checked at', value: "{{ format(now, 'dd.MM.yyyy HH:mm') }}" }] },
                { kind: 'context', text: 'Sent by MiniFlow' }];
            save();
            draw();
        });
        bar.appendChild(sel);
        bar.appendChild(preset);
        draw();
        wrap.appendChild(list);
        wrap.appendChild(bar);
        return wrap;
    }

    function renderField(f, t, fields) {
        const box = el('div', 'ff');
        const lab = el('label', 'ff-label');
        lab.appendChild(document.createTextNode(f.label || f.name));
        if (f.required) lab.appendChild(el('span', 'req', ' *'));
        const name = el('code', 'ff-name', f.name);
        lab.appendChild(name);
        box.appendChild(lab);

        const v = formParams[f.name];
        let control;
        let kind = f.kind;
        if ((kind === 'keyvalue' && v !== undefined && (typeof v !== 'object' || Array.isArray(v)))
            || (kind === 'list' && v !== undefined && !Array.isArray(v))) {
            kind = 'json';
        }
        switch (kind) {
            case 'textarea': control = textControl(f, true, false, 3); break;
            case 'code': control = textControl(f, true, true, 6); break;
            case 'boolean': {
                const c = el('input');
                c.type = 'checkbox';
                c.checked = v === undefined ? TRUE_BY_DEFAULT.indexOf(f.name) >= 0 : (v === true || v === 'true');
                c.addEventListener('change', () => setParam(f, c.checked));
                const l = el('label', 'ff-check');
                l.appendChild(c);
                l.appendChild(document.createTextNode(' ' + (f.label || f.name)));
                box.removeChild(lab);
                control = l;
                break;
            }
            case 'select': control = selectControl(f, f.options || [], t); break;
            case 'credential': {
                const types = t.credentialTypes || [];
                const names = credentials.filter(c => types.indexOf(c.type) >= 0).map(c => c.name);
                control = selectControl(f, names, t);
                break;
            }
            case 'keyvalue': control = keyValueControl(f); break;
            case 'list': control = listControl(f); break;
            case 'json': control = jsonControl(f); break;
            case 'slackBlocks': control = blocksControl(f); break;
            default: control = textControl(f, false, false); break;
        }
        box.appendChild(control);
        if (f.hint) box.appendChild(el('small', 'ff-hint', f.hint));
        return box;
    }

    function renderForm() {
        const node = selectedDfId && editor.getNodeFromId(selectedDfId);
        if (!node || !els.npForm) return;
        const t = typeInfo(node.data.type);
        const fields = fieldsOf(node.data.type);
        els.npForm.innerHTML = '';
        fields.filter(f => isVisible(f, fields)).forEach(f => els.npForm.appendChild(renderField(f, t, fields)));
        const extra = Object.keys(formParams).filter(k => !fields.some(f => f.name === k));
        if (extra.length) {
            const p = el('p', 'small muted ff-extra', T.formExtra + ' ');
            extra.forEach((k, i) => { p.appendChild(el('code', '', k)); if (i < extra.length - 1) p.appendChild(document.createTextNode(', ')); });
            els.npForm.appendChild(p);
        }
        els.npParams.value = JSON.stringify(formParams, null, 2);
    }

    if (els.npTabs) {
        els.npTabs.addEventListener('click', e => {
            const b = e.target.closest('.np-tab');
            if (b && b.dataset.tab !== activeTab) setTab(b.dataset.tab);
        });
        els.npForm.addEventListener('focusin', e => {
            if (e.target.matches('input[type=text], textarea')) lastField = e.target;
        });
    }

    const IDENT = /^[A-Za-z_][A-Za-z0-9_]*$/;
    const RESERVED = ['input', 'trigger', 'nodes', 'vars', 'now', 'today', 'nowMillis', 'true', 'false', 'null',
        'and', 'or', 'not', 'div', 'mod', 'eq', 'ne', 'lt', 'le', 'gt', 'ge', 'new', 'T',
        'join', 'default', 'format', 'round', 'sum', 'count', 'json'];

    function alias(nodeId) {
        let a = nodeId;
        if (!IDENT.test(a)) {
            a = '';
            let upper = false;
            for (const ch of nodeId) {
                if (ch === '-' || ch === ' ' || ch === '.') { upper = a.length > 0; continue; }
                a += upper ? ch.toUpperCase() : ch;
                upper = false;
            }
        }
        return IDENT.test(a) && RESERVED.indexOf(a) < 0 ? a : null;
    }

    function nodeRef(nodeId) {
        const a = alias(nodeId);
        return a || "nodes['" + nodeId.replace(/'/g, "\\'") + "']";
    }

    function childPath(path, key) {
        return IDENT.test(key) ? path + '.' + key : path + "['" + String(key).replace(/'/g, "\\'") + "']";
    }

    function upstream(dfId) {
        const data = allNodes();
        const seen = new Set([String(dfId)]);
        const order = [];
        let frontier = [String(dfId)];
        while (frontier.length) {
            const next = [];
            frontier.forEach(id => {
                const n = data[id];
                if (!n) return;
                Object.values(n.inputs || {}).forEach(inp => (inp.connections || []).forEach(c => {
                    const p = String(c.node);
                    if (!seen.has(p)) { seen.add(p); order.push(p); next.push(p); }
                }));
            });
            frontier = next;
        }
        return order.map(id => data[id]).filter(Boolean);
    }

    function sampleData() {
        let trigger = {};
        try { trigger = parseJsonLenient(els.testData.value || '{}'); } catch (e) {  }
        const nodes = {};
        Object.values(allNodes()).filter(n => n.data.type === 'trigger')
            .forEach(n => { nodes[n.data.nodeId] = trigger; });
        return { trigger: trigger, nodes: nodes, source: 'sample' };
    }

    function currentData() {
        return runData || sampleData();
    }

    function shortValue(v) {
        if (v === null || v === undefined) return 'null';
        if (Array.isArray(v)) return '[' + v.length + ']';
        if (typeof v === 'object') return '{…}';
        const s = String(v);
        return s.length > 40 ? s.slice(0, 40) + '…' : (s === '' ? '""' : s);
    }

    function leafRow(label, value, expr) {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'data-row';
        b.dataset.expr = expr;
        b.title = '{{ ' + expr + ' }}';
        b.innerHTML = '<span class="k">' + esc(label) + '</span><span class="v">' + esc(shortValue(value)) + '</span>';
        return b;
    }

    function renderValue(container, value, path, depth) {
        if (value === null || typeof value !== 'object') {
            container.appendChild(leafRow('=', value, path));
            return;
        }
        const entries = Array.isArray(value)
            ? (value.length ? [['[0]', value[0], path + '[0]']] : [])
            : Object.keys(value).slice(0, 60).map(k => [k, value[k], childPath(path, k)]);
        if (!entries.length) {
            const p = document.createElement('div');
            p.className = 'data-empty';
            p.textContent = T.dataEmpty;
            container.appendChild(p);
            return;
        }
        entries.forEach(([label, v, p]) => {
            if (v !== null && typeof v === 'object' && depth < 4) {
                const d = document.createElement('details');
                d.className = 'data-group nested';
                const sum = document.createElement('summary');
                const row = leafRow(label, v, p);
                row.classList.add('obj');
                row.insertAdjacentHTML('beforeend', '<span class="ins" title="{{ ' + esc(p) + ' }}">＋</span>');
                sum.appendChild(row);
                d.appendChild(sum);
                d.addEventListener('toggle', () => {
                    if (d.open && d.children.length === 1) renderValue(d, v, p, depth + 1);
                });
                container.appendChild(d);
            } else {
                container.appendChild(leafRow(label, v, p));
            }
        });
    }

    function group(title, sub, open, fill) {
        const d = document.createElement('details');
        d.className = 'data-group';
        d.open = open;
        d.innerHTML = '<summary><strong>' + esc(title) + '</strong>' + (sub ? ' <span class="muted small">' + esc(sub) + '</span>' : '') + '</summary>';
        let filled = false;
        const doFill = () => { if (!filled && d.open) { filled = true; fill(d); } };
        d.addEventListener('toggle', doFill);
        doFill();
        return d;
    }

    function renderDataPanel() {
        if (!selectedDfId || !els.npDataTree) return;
        const data = currentData();
        els.npDataSource.textContent = '· ' + (data.source === 'test' ? T.dataFromTest
            : data.source === 'history' ? T.dataFromHistory : T.dataFromSample);
        const tree = els.npDataTree;
        tree.innerHTML = '';

        const parents = upstream(selectedDfId);
        parents.forEach((n, i) => {
            const id = n.data.nodeId;
            const out = data.nodes[id];
            const sub = typeInfo(n.data.type).label + (i === 0 ? ' · ' + T.dataInput : '') + (out === undefined ? ' · ' + T.dataNotRun : '');
            tree.appendChild(group(nodeRef(id), sub, i === 0, d => {
                if (out === undefined) {
                    const p = document.createElement('div');
                    p.className = 'data-empty';
                    p.textContent = T.dataNotRun;
                    d.appendChild(p);
                } else {
                    renderValue(d, out, nodeRef(id), 0);
                }
            }));
        });
        const self = editor.getNodeFromId(selectedDfId);
        if (self && self.data.type !== 'trigger') {
            tree.appendChild(group('trigger', '', parents.length === 0, d => renderValue(d, data.trigger || {}, 'trigger', 0)));
        }
        const varNames = Object.keys(vars);
        tree.appendChild(group('vars', T.dataVars + ' (' + varNames.length + ')', false, d => {
            varNames.forEach(k => d.appendChild(leafRow(k, vars[k], childPath('vars', k))));
        }));
        tree.appendChild(group('ƒ', T.dataFunctions, false, d => {
            [['now', 'now'], ['today', 'today'],
             ["format(now, 'dd.MM.yyyy HH:mm')", 'format(now, ' + "'dd.MM.yyyy HH:mm')"],
             ["join(list, ', ')", "join(LIST, ', ')"], ["default(value, '-')", "default(VALUE, '-')"],
             ['round(number, 2)', 'round(NUMBER, 2)'], ['sum(list)', 'sum(LIST)'], ['count(list)', 'count(LIST)'],
             ['json(value)', 'json(VALUE)']]
                .forEach(([label, expr]) => d.appendChild(leafRow(label, '', expr)));
        }));
    }

    function insideString(text, pos) {
        let inString = false, escaped = false;
        for (let i = 0; i < pos; i++) {
            const ch = text[i];
            if (escaped) { escaped = false; continue; }
            if (ch === '\\') { escaped = true; continue; }
            if (ch === '"') inString = !inString;
        }
        return inString;
    }

    function insertExpression(expr) {
        if (formActive()) {
            if (lastField && document.body.contains(lastField)) {
                const el = lastField;
                const text = '{{ ' + expr + ' }}';
                const start = el.selectionStart != null ? el.selectionStart : el.value.length;
                const end = el.selectionEnd != null ? el.selectionEnd : start;
                el.value = el.value.slice(0, start) + text + el.value.slice(end);
                el.focus();
                const ph = text.search(/LIST|VALUE|NUMBER/);
                if (ph >= 0) {
                    const len = (text.match(/LIST|VALUE|NUMBER/) || [''])[0].length;
                    el.setSelectionRange(start + ph, start + ph + len);
                } else {
                    el.setSelectionRange(start + text.length, start + text.length);
                }
                el.dispatchEvent(new Event('input', { bubbles: true }));
                return;
            }
            lastCaret = null;
        }
        const ta = els.npParams;
        if (!lastCaret) {
            const text = '{{ ' + expr + ' }}';
            try { navigator.clipboard.writeText(text); } catch (e) {  }
            els.npDataSource.textContent = '· ' + T.dataCopied.replace('{0}', text);
            ta.focus();
            return;
        }
        const [start, end] = lastCaret;
        let text = '{{ ' + expr + ' }}';
        if (!insideString(ta.value, start)) text = '"' + text + '"';
        ta.value = ta.value.slice(0, start) + text + ta.value.slice(end);
        ta.focus();
        const ph = text.search(/LIST|VALUE|NUMBER/);
        if (ph >= 0) {
            const len = (text.match(/LIST|VALUE|NUMBER/) || [''])[0].length;
            ta.setSelectionRange(start + ph, start + ph + len);
        } else {
            ta.setSelectionRange(start + text.length, start + text.length);
        }
        lastCaret = [ta.selectionStart, ta.selectionEnd];
        schedulePreview();
    }

    let previewTimer = null;
    let previewSeq = 0;

    function schedulePreview(delay) {
        clearTimeout(previewTimer);
        previewTimer = setTimeout(updatePreview, delay === undefined ? 350 : delay);
    }

    async function updatePreview() {
        if (!selectedDfId || !els.npPreview) return;
        let params;
        try { params = parseJsonLenient(els.npParams.value || '{}'); } catch (e) { els.npPreview.hidden = true; return; }
        if (!params || typeof params !== 'object' || Array.isArray(params)) { els.npPreview.hidden = true; return; }
        const keys = Object.keys(params).filter(k => JSON.stringify(params[k]).indexOf('{{') >= 0);
        if (!keys.length) { els.npPreview.hidden = true; return; }
        const subset = {};
        keys.forEach(k => { subset[k] = params[k]; });

        const data = currentData();
        const parent = upstream(selectedDfId).find(n => data.nodes[n.data.nodeId] !== undefined);
        const self = editor.getNodeFromId(selectedDfId);
        const input = parent ? data.nodes[parent.data.nodeId]
            : (self && self.data.type === 'trigger' ? data.trigger : {});
        const seq = ++previewSeq;
        let result;
        try {
            const res = await fetch(base + 'api/expressions/preview', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ params: subset, input: input || {}, trigger: data.trigger || {}, nodes: data.nodes || {} })
            });
            if (!res || !res.ok) return;
            result = await res.json();
        } catch (e) {
            return;
        }
        if (seq !== previewSeq || !result) return;
        const list = els.npPreviewList;
        list.innerHTML = '';
        keys.forEach(k => {
            const dt = document.createElement('dt');
            dt.textContent = k;
            const dd = document.createElement('dd');
            const err = result.errors && result.errors[k];
            if (err) {
                dd.className = 'err';
                dd.textContent = err;
            } else {
                const v = result.values ? result.values[k] : null;
                let text = v === null || v === undefined ? 'null' : (typeof v === 'string' ? v : JSON.stringify(v, null, 2));
                if (text.length > 600) text = text.slice(0, 600) + '…';
                dd.textContent = text;
                if (v === null || v === undefined || v === '') dd.className = 'warn';
            }
            list.appendChild(dt);
            list.appendChild(dd);
        });
        els.npPreview.hidden = false;
    }

    async function loadLastRun() {
        try {
            const res = await fetch(base + 'api/workflows/' + encodeURIComponent(wf.id) + '/last-run');
            if (res && res.status === 200) {
                const d = await res.json();
                if (!runData) runData = { trigger: d.trigger || {}, nodes: d.nodes || {}, source: 'history' };
            }
        } catch (e) {  }
        if (selectedDfId) { renderDataPanel(); schedulePreview(0); }
    }

    if (els.npDataTree) {
        els.npDataTree.addEventListener('click', e => {
            const row = e.target.closest('.data-row');
            if (!row) return;
            if (row.classList.contains('obj') && !e.target.closest('.ins')) {
                const details = row.closest('details');
                if (details) { e.preventDefault(); details.open = !details.open; }
                return;
            }
            e.preventDefault();
            insertExpression(row.dataset.expr);
        });
    }
    ['keyup', 'click', 'blur', 'select'].forEach(ev => els.npParams.addEventListener(ev, () => {
        lastCaret = [els.npParams.selectionStart, els.npParams.selectionEnd];
    }));
    els.npParams.addEventListener('input', () => schedulePreview());
    els.testData.addEventListener('input', () => {
        if (runData && runData.source !== 'sample') return;
        runData = null;
        if (selectedDfId) { renderDataPanel(); schedulePreview(); }
    });

    document.querySelectorAll('.palette-item').forEach(btn => {
        btn.addEventListener('click', () => {
            const type = btn.dataset.type;
            const t = typeInfo(type);
            const rect = els.canvas.getBoundingClientRect();
            const zoom = editor.zoom || 1;
            const x = (rect.width / 2 - 90 - editor.canvas_x) / zoom;
            const y = (rect.height / 2 - 40 - editor.canvas_y) / zoom;
            const params = JSON.parse(JSON.stringify(t.defaultParams || {}));
            addNode(type, uniqueNodeId(type), params, Math.round(x), Math.round(y));
        });
    });

    editor.on('nodeSelected', id => showNode(id));
    editor.on('nodeUnselected', () => hideNode());
    editor.on('nodeRemoved', id => { if (String(id) === selectedDfId) hideNode(); markDirty(); });
    ['nodeCreated', 'nodeMoved', 'connectionCreated', 'connectionRemoved'].forEach(ev => editor.on(ev, markDirty));
    els.name.addEventListener('input', markDirty);
    els.active.addEventListener('change', () => {
        save()
            .then(() => setStatus(els.active.checked ? T.active : T.inactive, 'ok'))
            .catch(e => {
                els.active.checked = !els.active.checked;
                setStatus(e.message, 'err');
            });
    });

    els.npApply.addEventListener('click', applyNode);
    els.npDelete.addEventListener('click', () => {
        if (selectedDfId) editor.removeNodeId('node-' + selectedDfId);
    });

    els.save.addEventListener('click', () => save().catch(e => setStatus(e.message, 'err')));
    els.run.addEventListener('click', run);

    document.addEventListener('keydown', e => {
        if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
            e.preventDefault();
            save().catch(err => setStatus(err.message, 'err'));
        }
    });

    window.addEventListener('beforeunload', e => {
        if (dirty) { e.preventDefault(); e.returnValue = ''; }
    });

    load();
    loadLastRun();
})();
