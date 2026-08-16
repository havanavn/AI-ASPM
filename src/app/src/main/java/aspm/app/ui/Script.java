package aspm.app.ui;

/**
 * First-party progressive enhancement. ADR-058, {@code PRD-UIX-013}.
 *
 * <p>Everything here is an enhancement of something that already works. {@code PRD-UIX-013} forbids a
 * pointer-only capability, and a script-only capability is the same defect with a different cause: a
 * caller with script blocked would lose the action rather than lose polish.
 *
 * <ul>
 *   <li>The command interface is a {@code <dialog>} listing links that also exist in the sidebar.
 *   <li>Theme and density are {@code <select>} elements inside a {@code <form method=get>}, so without
 *       script they submit and the server could honour them. <b>Neither is persisted</b>
 *       ({@code PRD-UIX-007} requires persistence and it is not implemented), which is why the labels
 *       promise nothing about it.
 *   <li>List navigation moves focus between rows that are already reachable by Tab.
 * </ul>
 *
 * <h2>A correction: this file is a Java text block, and {@code \\n} is not a JavaScript escape here</h2>
 *
 * <p>The Markdown toolbar's actions were written as {@code '\\n### '}. A text block interprets
 * {@code \\n} itself, so what reached the browser was a real line break inside a single-quoted
 * JavaScript string — a syntax error on line 94 of the served file. <b>The whole script therefore
 * never parsed, and every enhancement in it has been dead since it was written</b>: the toolbar, the
 * live preview, the command dialog, list navigation. Nothing failed loudly, because the file is
 * progressive enhancement and a page with no script is a page that still works.
 *
 * <p>It stayed invisible because the console was already full: the Content Security Policy was
 * blocking dozens of inline styles per page, and one syntax error scrolled past. Fixing the styles is
 * what surfaced it. Escapes intended for JavaScript are written {@code \\\\n} from here on.
 */
public final class Script {

    private Script() {
    }

    public static String js() {
        return """
            // AI-ASPM interface enhancement. DOC-08 §7.1. Nothing here is required for any capability.
            'use strict';

            (function () {
              var root = document.documentElement;

              // ---- Theme and density. A change applies immediately; no page reload, no persistence.
              document.querySelectorAll('select[data-pref]').forEach(function (select) {
                select.addEventListener('change', function () {
                  var key = select.getAttribute('data-pref');
                  if (select.value) { root.setAttribute('data-' + key, select.value); }
                  else { root.removeAttribute('data-' + key); }
                });
              });

              // ---- Command interface. DOC-08 §7.1: a single shortcut from anywhere.
              var dialog = document.getElementById('cmdk');
              var input = document.getElementById('cmd-input');
              var trigger = document.getElementById('cmd-open');
              var items = dialog ? Array.prototype.slice.call(
                  dialog.querySelectorAll('[data-cmd]')) : [];

              function openPalette() {
                if (!dialog || dialog.open) { return; }
                dialog.showModal();
                if (input) { input.value = ''; filter(''); input.focus(); }
              }

              function filter(term) {
                var needle = term.trim().toLowerCase();
                items.forEach(function (item) {
                  var text = (item.textContent || '').toLowerCase();
                  item.hidden = needle.length > 0 && text.indexOf(needle) === -1;
                });
              }

              if (trigger) { trigger.addEventListener('click', openPalette); }
              if (input) {
                input.addEventListener('input', function () { filter(input.value); });
                // Enter opens the first visible target, so the palette is usable without a pointer
                // and without arrow keys.
                input.addEventListener('keydown', function (event) {
                  if (event.key !== 'Enter') { return; }
                  var first = items.filter(function (i) { return !i.hidden; })[0];
                  if (first) { event.preventDefault(); first.click(); }
                });
              }

              document.addEventListener('keydown', function (event) {
                var mod = event.ctrlKey || event.metaKey;
                if (mod && event.key.toLowerCase() === 'k') {
                  event.preventDefault();
                  openPalette();
                  return;
                }
                // Focus restoration on close is what <dialog> gives us; DOC-08 §7.1 requires it and
                // implementing it by hand is how it stops working.
                if (event.key === 'Escape' && dialog && dialog.open) { dialog.close(); }
              });

              // ---- List navigation without a pointer. The density affordance ADR-006 asks for; the
              // rows are already focusable in document order, so this is movement and not capability.
              var rows = Array.prototype.slice.call(document.querySelectorAll('table.data tbody tr'));
              if (rows.length === 0) { return; }
              document.addEventListener('keydown', function (event) {
                if (event.ctrlKey || event.metaKey || event.altKey) { return; }
                if (dialog && dialog.open) { return; }
                var tag = (event.target.tagName || '').toLowerCase();
                if (tag === 'input' || tag === 'select' || tag === 'textarea') { return; }
                var index = rows.indexOf(document.activeElement);
                var next = null;
                if (event.key === 'j' || event.key === 'ArrowDown') { next = index + 1; }
                if (event.key === 'k' || event.key === 'ArrowUp') { next = index - 1; }
                if (next === null) { return; }
                event.preventDefault();
                rows[Math.max(0, Math.min(rows.length - 1, next))].focus();
              });
            })();

              /* ---- Markdown editor: a toolbar and a preview, both added only if script runs ----
                 The textarea works without any of this. These controls insert Markdown text; they
                 never produce markup, and the preview writes with textContent rather than innerHTML
                 so a payload being written up cannot execute in the editor that is describing it. */
              document.querySelectorAll('[data-md-editor]').forEach(function (host) {
                var input = host.querySelector('[data-md-input]');
                if (!input) { return; }

                var bar = document.createElement('div');
                bar.className = 'md-toolbar';
                var actions = [
                  ['editor.bold', '**', '**', 'bold'],
                  ['editor.italic', '_', '_', 'italic'],
                  ['editor.heading', '\\n### ', '', 'heading'],
                  ['editor.list', '\\n- ', '', 'list'],
                  ['editor.code', '\\n```\\n', '\\n```\\n', 'code'],
                  ['editor.link', '[', '](https://)', 'link'],
                  ['editor.table',
                   '\\n| Field | Value |\\n| --- | --- |\\n| ', ' |  |\\n', 'table']
                ];
                actions.forEach(function (a) {
                  var b = document.createElement('button');
                  b.type = 'button';
                  b.textContent = a[3];
                  b.addEventListener('click', function () {
                    var start = input.selectionStart, end = input.selectionEnd;
                    var chosen = input.value.slice(start, end);
                    input.value = input.value.slice(0, start) + a[1] + chosen + a[2]
                                  + input.value.slice(end);
                    input.focus();
                    input.selectionStart = input.selectionEnd = start + a[1].length + chosen.length;
                    input.dispatchEvent(new Event('input'));
                  });
                  bar.appendChild(b);
                });

                /* ---- Inline images ----
                   A file picker, a paste handler and a drop target, all three doing the same thing:
                   downscale, upload, insert the Markdown the server hands back at the cursor. Paste
                   is the one that matters — a pentester screenshots a repro and pastes it, and any
                   flow that made them save a file first would be abandoned for a description in
                   prose that nobody can reproduce from.

                   The image is NEVER inserted as a data: URI. What goes into the textarea is a
                   reference to a stored object the server has already sniffed and typed; embedding
                   the bytes would put megabytes of attacker-influenced base64 into a field that gets
                   rendered, and would make the size cap unenforceable. */
                var endpoint = host.getAttribute('data-md-upload');
                if (endpoint) {
                  var finding = host.getAttribute('data-md-finding');
                  var status = document.createElement('span');
                  status.className = 'fs-11 muted md-upload-status';

                  var picker = document.createElement('input');
                  picker.type = 'file';
                  picker.accept = 'image/png,image/jpeg,image/gif,image/webp';
                  picker.hidden = true;
                  host.appendChild(picker);

                  function insertAt(text) {
                    var start = input.selectionStart, end = input.selectionEnd;
                    var before = input.value.slice(0, start);
                    var pad = before && !before.endsWith('\\n') ? '\\n\\n' : '';
                    input.value = before + pad + text + '\\n' + input.value.slice(end);
                    input.focus();
                    input.selectionStart = input.selectionEnd =
                      start + pad.length + text.length + 1;
                  }

                  /* Downscale before upload. The cap is 1 MiB and a modern screenshot exceeds it, so
                     without this the honest answer to most pastes would be a refusal. 1600px on the
                     long edge keeps a code screenshot legible. */
                  function shrink(file) {
                    return new Promise(function (resolve, reject) {
                      var url = URL.createObjectURL(file);
                      var img = new Image();
                      img.onload = function () {
                        URL.revokeObjectURL(url);
                        var scale = Math.min(1, 1600 / Math.max(img.width, img.height));
                        var canvas = document.createElement('canvas');
                        canvas.width = Math.max(1, Math.round(img.width * scale));
                        canvas.height = Math.max(1, Math.round(img.height * scale));
                        canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
                        /* PNG, always. The canvas re-encode is also what strips EXIF: a screenshot
                           of a staging environment can carry location and device metadata, and a
                           finding write-up is read by more people than the person who pasted it. */
                        resolve(canvas.toDataURL('image/png'));
                      };
                      img.onerror = function () {
                        URL.revokeObjectURL(url);
                        reject(new Error('not an image'));
                      };
                      img.src = url;
                    });
                  }

                  function send(file) {
                    if (!file || file.type.indexOf('image/') !== 0) { return; }
                    status.textContent = '…';
                    shrink(file).then(function (dataUrl) {
                      var body = new URLSearchParams();
                      body.set('data', dataUrl);
                      body.set('filename', file.name || 'pasted.png');
                      body.set('idempotency_key', crypto.randomUUID());
                      if (finding) { body.set('finding', finding); }
                      return fetch(endpoint, {
                        method: 'POST',
                        credentials: 'same-origin',
                        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                        body: body.toString()
                      });
                    }).then(function (response) {
                      if (!response.ok) { throw new Error('rejected'); }
                      return response.json();
                    }).then(function (payload) {
                      status.textContent = '';
                      insertAt(payload.markdown);
                    }).catch(function () {
                      /* Loudly, not silently (PP-9). A paste that appears to work and produces no
                         image is worse than one that says it failed. */
                      status.textContent = '✕';
                    });
                  }

                  picker.addEventListener('change', function () {
                    send(picker.files && picker.files[0]);
                    picker.value = '';
                  });

                  input.addEventListener('paste', function (event) {
                    var items = (event.clipboardData || {}).items || [];
                    for (var i = 0; i < items.length; i++) {
                      if (items[i].type.indexOf('image/') === 0) {
                        event.preventDefault();
                        send(items[i].getAsFile());
                        return;
                      }
                    }
                  });

                  ['dragover', 'drop'].forEach(function (name) {
                    input.addEventListener(name, function (event) {
                      event.preventDefault();
                      host.classList.toggle('is-dropping', name === 'dragover');
                      if (name === 'drop') {
                        send(event.dataTransfer && event.dataTransfer.files[0]);
                      }
                    });
                  });
                  input.addEventListener('dragleave', function () {
                    host.classList.remove('is-dropping');
                  });

                  var imageButton = document.createElement('button');
                  imageButton.type = 'button';
                  imageButton.textContent = 'image';
                  imageButton.addEventListener('click', function () { picker.click(); });
                  bar.appendChild(imageButton);
                  bar.appendChild(status);
                }

                var toggle = document.createElement('button');
                toggle.type = 'button';
                toggle.textContent = 'preview';
                bar.appendChild(toggle);
                host.insertBefore(bar, input);

                var preview = document.createElement('pre');
                preview.className = 'md-preview md-code';
                preview.hidden = true;
                host.appendChild(preview);
                toggle.addEventListener('click', function () {
                  preview.hidden = !preview.hidden;
                  /* textContent, never innerHTML. The server decides what markup exists; this only
                     shows the source the way it will be submitted. */
                  preview.textContent = input.value;
                });
              });
            """;
    }
}
