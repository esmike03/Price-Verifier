/* ==========================================================================
   Price Verifier Content Portal — UI behaviour
   Progressive enhancement: every feature here has a plain-HTML fallback.
   ========================================================================== */
(function () {
    'use strict';

    var cfg = window.PV_CONFIG || {};
    var $ = function (sel, root) { return (root || document).querySelector(sel); };
    var $$ = function (sel, root) {
        return Array.prototype.slice.call((root || document).querySelectorAll(sel));
    };

    function store(key, value) {
        try {
            if (value === undefined) { return localStorage.getItem(key); }
            localStorage.setItem(key, value);
        } catch (e) { /* private mode / blocked storage */ }
        return null;
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function debounce(fn, wait) {
        var timer = null;
        return function () {
            var args = arguments, self = this;
            clearTimeout(timer);
            timer = setTimeout(function () { fn.apply(self, args); }, wait);
        };
    }

    /* --- Theme ---------------------------------------------------------- */
    (function theme() {
        var toggle = $('[data-theme-toggle]');
        if (!toggle) { return; }
        var media = window.matchMedia('(prefers-color-scheme: dark)');

        function apply(mode, persist) {
            var dark = mode === 'dark';
            document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
            toggle.setAttribute('aria-label', dark ? 'Switch to light theme' : 'Switch to dark theme');
            toggle.setAttribute('title', dark ? 'Light theme' : 'Dark theme');
            $$('[data-theme-icon]', toggle).forEach(function (icon) {
                icon.hidden = icon.getAttribute('data-theme-icon') !== (dark ? 'dark' : 'light');
            });
            if (persist) { store('pvTheme', mode); }
        }

        var saved = store('pvTheme');
        apply(saved === 'dark' || saved === 'light' ? saved : (media.matches ? 'dark' : 'light'), false);

        media.addEventListener('change', function (event) {
            if (!store('pvTheme')) { apply(event.matches ? 'dark' : 'light', false); }
        });

        toggle.addEventListener('click', function () {
            apply(document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark', true);
        });
    })();

    /* --- Dismissible notices -------------------------------------------- */
    $$('.notice').forEach(function (notice) {
        var close = $('[data-dismiss]', notice);
        if (!close) { return; }
        close.addEventListener('click', function () {
            notice.style.transition = 'opacity .18s ease, transform .18s ease';
            notice.style.opacity = '0';
            notice.style.transform = 'translateY(-6px)';
            setTimeout(function () { notice.remove(); }, 200);
        });
    });

    /* --- Confirm-before-submit ------------------------------------------ */
    $$('form[data-confirm]').forEach(function (form) {
        form.addEventListener('submit', function (event) {
            if (!window.confirm(form.getAttribute('data-confirm'))) { event.preventDefault(); }
        });
    });

    /* --- Tabs ------------------------------------------------------------ */
    (function tabs() {
        var buttons = $$('.tab-btn');
        var panels = $$('.tab-panel');
        if (!buttons.length) { return; }

        function activate(name, persist) {
            var matched = false;
            panels.forEach(function (panel) {
                var on = panel.getAttribute('data-tab') === name;
                panel.classList.toggle('active', on);
                panel.hidden = !on;
                if (on) { matched = true; }
            });
            buttons.forEach(function (button) {
                var on = button.getAttribute('data-tab') === name;
                button.classList.toggle('active', on);
                button.setAttribute('aria-selected', on ? 'true' : 'false');
                button.setAttribute('tabindex', on ? '0' : '-1');
            });
            if (matched && persist) { store('pvTab', name); }
            return matched;
        }

        buttons.forEach(function (button, index) {
            button.addEventListener('click', function () {
                activate(button.getAttribute('data-tab'), true);
            });
            // Left/right arrows move between tabs, per WAI-ARIA tab pattern.
            button.addEventListener('keydown', function (event) {
                var step = event.key === 'ArrowRight' ? 1 : (event.key === 'ArrowLeft' ? -1 : 0);
                if (!step) { return; }
                event.preventDefault();
                var next = buttons[(index + step + buttons.length) % buttons.length];
                next.focus();
                activate(next.getAttribute('data-tab'), true);
            });
        });

        var initial = (cfg.forcedTab || '') || store('pvTab') || 'media';
        if (!activate(initial, false)) { activate('media', false); }
    })();

    /* --- Upload cards ---------------------------------------------------- */
    $$('form[data-upload]').forEach(function (form) {
        var input = $('input[type="file"]', form);
        var status = $('[data-file-status]', form);
        var submit = $('button[type="submit"]', form);

        if (input && status) {
            input.addEventListener('change', function () {
                var files = input.files;
                if (!files || !files.length) { status.textContent = ''; return; }
                var total = 0;
                for (var i = 0; i < files.length; i++) { total += files[i].size; }
                var label = files.length === 1 ? files[0].name : files.length + ' files selected';
                status.textContent = label + ' · ' + humanBytes(total);
            });
        }

        form.addEventListener('submit', function (event) {
            // A data-confirm the user cancelled has already prevented the submit.
            if (event.defaultPrevented) { return; }
            // The catalog upload asks first, in words that match the chosen option:
            // replacing removes everything that is not in the file.
            if (form.hasAttribute('data-catalog-upload')) {
                var mode = $('input[name="catalog_mode"]:checked', form);
                var fileName = input && input.files && input.files.length ? input.files[0].name : 'this file';
                var count = parseInt(form.getAttribute('data-catalog-count'), 10);
                var message;
                if (mode && mode.value === 'merge') {
                    message = 'Add and update items from "' + fileName + '"?\n\n'
                        + 'Items in the file are added or updated by barcode. Everything else in the catalog is kept.';
                } else {
                    message = 'REPLACE the whole catalog with "' + fileName + '"?\n\n'
                        + (count > 0
                            ? 'The current catalog of ' + count.toLocaleString() + ' items will be removed from the server.'
                            : 'The current catalog will be removed from the server.')
                        + ' Only the items in this file will remain.\n\n'
                        + 'To keep the current items, cancel and choose "Add and update only".';
                }
                if (!window.confirm(message)) { event.preventDefault(); return; }
            }
            if (!submit) { return; }
            submit.disabled = true;
            submit.innerHTML = '<span class="spinner" aria-hidden="true"></span>Uploading…';
        });
    });

    function humanBytes(bytes) {
        if (bytes >= 1048576) { return (bytes / 1048576).toFixed(1) + ' MB'; }
        if (bytes >= 1024) { return (bytes / 1024).toFixed(1) + ' KB'; }
        return bytes + ' B';
    }

    /* --- Modal support -----------------------------------------------------
       Real <dialog> modals need JS to open/close them; without JS, a modal
       either sits inline (item modal, which the server can render `open`)
       or simply isn't reachable (media modal), so every modal-dependent
       trigger below bails out cleanly when this returns false. */
    function supportsDialog() {
        return typeof HTMLDialogElement === 'function'
            && typeof HTMLDialogElement.prototype.showModal === 'function';
    }

    // Click-to-dismiss: a click that lands on the dialog element itself
    // (rather than something inside it) means the backdrop was clicked.
    function wireModalDismiss(dialog) {
        $$('[data-modal-close]', dialog).forEach(function (button) {
            button.addEventListener('click', function () { dialog.close(); });
        });
        dialog.addEventListener('click', function (event) {
            if (event.target === dialog) { dialog.close(); }
        });
    }

    /* --- Display media: pop each slot open as a modal instead of an
       inline dropdown. The slot stays a native <details>/<summary> pair
       (so it still works with JS off); with JS on, opening it moves its
       body into a shared dialog instead of expanding in place. --------- */
    (function mediaModals() {
        var dialog = $('#media-modal');
        if (!dialog || !supportsDialog()) { return; }

        var titleEl = $('[data-modal-title]', dialog);
        var bodyEl = $('[data-modal-body]', dialog);
        var parked = null; // { body, home } — where the moved content came from

        function restore() {
            if (parked) {
                parked.home.appendChild(parked.body);
                parked = null;
            }
        }

        $$('.grid > details.card').forEach(function (details) {
            var summary = $('summary', details);
            var body = $('.card-body', details);
            if (!summary || !body) { return; }
            summary.addEventListener('click', function (event) {
                event.preventDefault();
                restore(); // safety net if a previous move never came home
                parked = { body: body, home: details };
                if (titleEl) {
                    var label = $('h2', summary);
                    titleEl.textContent = label ? label.textContent : '';
                }
                bodyEl.appendChild(body);
                dialog.showModal();
            });
        });

        dialog.addEventListener('close', restore);
        wireModalDismiss(dialog);
    })();

    /* --- Catalog: add/edit a product in a modal instead of an always-on
       inline form. The form itself lives inside the dialog and is reused
       for both adding and editing — only its values and labels change. -- */
    (function itemModal() {
        var dialog = $('#item-modal');
        if (!dialog || !supportsDialog()) { return; }

        var form = $('#item-form', dialog);
        var titleEl = $('.modal-head h2', dialog);
        var subtitleEl = $('[data-item-modal-subtitle]', dialog);
        var submitBtn = $('[data-item-modal-submit]', dialog);
        var deleteForm = $('[data-item-delete-form]', dialog);
        var deleteBarcode = deleteForm ? $('[data-item-delete-barcode]', deleteForm) : null;
        if (!form) { return; }

        var fields = {
            barcode: $('#item-barcode', dialog),
            name: $('#item-name', dialog),
            uom: $('#item-uom', dialog),
            category: $('#item-category', dialog),
            price: $('#item-price', dialog),
            item_code: $('#item-code', dialog)
        };

        // The dialog may load already `open` (server-rendered fallback for
        // no-JS visitors and direct "edit" links) — normalize to closed so
        // showModal() can be used cleanly from here on.
        var autopopen = dialog.hasAttribute('data-autopopen');
        if (dialog.open) { dialog.close(); }

        function openFor(values) {
            var editing = !!(values && values.barcode);
            form.reset();
            Object.keys(fields).forEach(function (key) {
                if (fields[key]) { fields[key].value = (values && values[key]) || ''; }
            });
            if (titleEl) { titleEl.textContent = editing ? 'Edit catalog item' : 'Add a catalog item'; }
            if (subtitleEl) {
                subtitleEl.textContent = editing
                    ? 'Editing the selected hosted item.'
                    : 'An existing barcode will be updated in the hosted catalog.';
            }
            if (submitBtn) { submitBtn.textContent = editing ? 'Save item' : 'Add or update item'; }
            if (deleteForm) {
                deleteForm.hidden = !editing;
                if (deleteBarcode) { deleteBarcode.value = editing ? values.barcode : ''; }
                if (editing) {
                    deleteForm.setAttribute('data-confirm',
                        'Delete ' + (values.name || values.barcode) + ' from the catalog?');
                }
            }
            dialog.showModal();
            if (fields.barcode) { fields.barcode.focus(); }
        }

        $$('[data-open-add-item]').forEach(function (button) {
            button.addEventListener('click', function () { openFor(null); });
        });

        // Edit links may live in the initial markup or in AJAX-rendered rows,
        // so listen on the document instead of binding to specific elements.
        // Values come from the row's data-* attributes (the raw catalog values),
        // not the cells, which hold display formatting like "₱1,250.00".
        // Clicking anywhere on a row edits it too, unless the user is
        // selecting text to copy a barcode.
        document.addEventListener('click', function (event) {
            var link = event.target.closest('[data-edit-item]');
            var row = event.target.closest('[data-catalog] tr[data-barcode]');
            if (!row) { return; }
            if (!link) {
                if (event.target.closest('a, button, input')) { return; }
                var selection = window.getSelection ? String(window.getSelection()) : '';
                if (selection) { return; }
            }
            event.preventDefault();
            var data = row.dataset;
            openFor({
                barcode: data.barcode || '',
                name: data.name || '',
                uom: data.uom || '',
                category: data.category || '',
                price: data.price || '',
                item_code: data.itemCode || ''
            });
        });

        wireModalDismiss(dialog);

        if (autopopen) {
            openFor({
                barcode: fields.barcode ? fields.barcode.value : '',
                name: fields.name ? fields.name.value : '',
                uom: fields.uom ? fields.uom.value : '',
                category: fields.category ? fields.category.value : '',
                price: fields.price ? fields.price.value : '',
                item_code: fields.item_code ? fields.item_code.value : ''
            });
        }
    })();

    /* --- Live catalog search --------------------------------------------- */
    (function catalogSearch() {
        var root = $('[data-catalog]');
        if (!root) { return; }

        var input = $('[data-catalog-search]', root);
        var clear = $('[data-search-clear]', root);
        var field = input ? input.closest('.search-field') : null;
        var tbody = $('[data-catalog-body]', root);
        var wrap = $('.table-wrap', root);
        var meta = $('[data-catalog-meta]', root);
        var pager = $('[data-catalog-pager]', root);
        var icons = cfg.icons || {};
        var pageSize = parseInt(root.getAttribute('data-page-size'), 10) || 50;
        if (!input || !tbody) { return; }

        var state = {
            q: input.value,
            page: parseInt(root.getAttribute('data-page'), 10) || 1,
            pages: parseInt(root.getAttribute('data-pages'), 10) || 1,
            editing: root.getAttribute('data-editing') || ''
        };
        var controller = null;
        var requestId = 0;

        // The server prints times in its own zone; show the viewer's local time.
        $$('time[data-local]').forEach(function (el) {
            var date = new Date(el.getAttribute('datetime'));
            if (isNaN(date.getTime())) { return; }
            el.textContent = date.toLocaleString(undefined, {
                month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit'
            });
        });

        function markFilled() {
            if (field) { field.setAttribute('data-filled', input.value ? '1' : '0'); }
        }

        function highlight(value, needle) {
            var text = escapeHtml(value);
            if (!needle) { return text; }
            var pattern = needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
            try {
                return text.replace(new RegExp('(' + pattern + ')', 'gi'), '<mark class="hit">$1</mark>');
            } catch (e) {
                return text;
            }
        }

        function editHref(barcode) {
            return '?edit=' + encodeURIComponent(barcode)
                + '&q=' + encodeURIComponent(state.q)
                + '&page=' + state.page;
        }

        // Mirrors catalogPriceLabel() in index.php.
        function priceLabel(value) {
            var clean = String(value || '').replace(/[,\s₱]/g, '');
            if (clean === '' || isNaN(clean)) { return String(value || ''); }
            return '₱' + Number(clean).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        }

        function emptyState(needle) {
            return '<tr class="empty-row"><td colspan="6"><div class="empty-state">'
                + '<span class="empty-icon">' + (needle ? icons.search : icons.box) + '</span>'
                + (needle
                    ? '<strong>No products match “' + escapeHtml(needle) + '”</strong>'
                      + '<span class="subtle">Check the spelling, or search with part of the barcode or name.</span>'
                    : '<strong>No products yet</strong>'
                      + '<span class="subtle">Upload catalog.csv on the Display media tab, or add a product.</span>')
                + '</div></td></tr>';
        }

        function rowHtml(item, needle) {
            var editing = state.editing && item.barcode === state.editing;
            var attrs = [['barcode', item.barcode], ['name', item.name], ['uom', item.uom],
                ['category', item.category], ['price', item.price], ['item-code', item.item_code]]
                .map(function (pair) { return ' data-' + pair[0] + '="' + escapeHtml(pair[1] || '') + '"'; })
                .join('');
            return '<tr class="catalog-row' + (editing ? ' is-editing' : '') + '"' + attrs + '>'
                + '<td class="cell-product"><span class="product-name">' + highlight(item.name, needle) + '</span>'
                + '<span class="product-barcode">' + highlight(item.barcode, needle) + '</span></td>'
                + '<td class="cell-category">' + (item.category
                    ? '<span class="chip">' + highlight(item.category, needle) + '</span>' : '') + '</td>'
                + '<td class="cell-uom">' + highlight(item.uom, needle) + '</td>'
                + '<td class="cell-code">' + highlight(item.item_code, needle) + '</td>'
                + '<td class="cell-price">' + escapeHtml(priceLabel(item.price)) + '</td>'
                + '<td class="actions-cell"><a class="icon-btn row-edit" data-edit-item title="Edit"'
                + ' aria-label="Edit ' + escapeHtml(item.name) + '" href="' + escapeHtml(editHref(item.barcode)) + '">'
                + icons.edit + '</a></td>'
                + '</tr>';
        }

        function render(data) {
            var needle = data.q || '';
            tbody.innerHTML = data.items.length
                ? data.items.map(function (item) { return rowHtml(item, needle); }).join('')
                : emptyState(needle);

            state.page = data.page;
            state.pages = data.pages;
            root.setAttribute('data-page', String(data.page));
            root.setAttribute('data-pages', String(data.pages));

            if (meta) {
                var first = (data.page - 1) * pageSize + 1;
                meta.textContent = data.total === 0 ? 'No results'
                    : first.toLocaleString() + '–' + (first + data.shown - 1).toLocaleString()
                      + ' of ' + data.total.toLocaleString() + (needle ? ' matches' : ' products');
            }
            renderPager();
        }

        function renderPager() {
            if (!pager) { return; }
            pager.hidden = state.pages <= 1;
            pager.innerHTML = '<span class="subtle">Page ' + state.page.toLocaleString()
                + ' of ' + state.pages.toLocaleString() + '</span>'
                + '<div class="actions">'
                + '<button type="button" class="secondary btn-sm" data-page-step="-1"'
                + (state.page > 1 ? '' : ' disabled') + '>' + icons.prev + ' Previous</button>'
                + '<button type="button" class="secondary btn-sm" data-page-step="1"'
                + (state.page < state.pages ? '' : ' disabled') + '>Next ' + icons.next + '</button>'
                + '</div>';
        }

        function syncUrl() {
            if (!window.history || !window.history.replaceState) { return; }
            var params = [];
            if (state.q) { params.push('q=' + encodeURIComponent(state.q)); }
            if (state.page > 1) { params.push('page=' + state.page); }
            if (state.editing) { params.push('edit=' + encodeURIComponent(state.editing)); }
            window.history.replaceState(null, '', params.length ? '?' + params.join('&') : location.pathname);
        }

        function fetchPage() {
            var id = ++requestId;
            if (controller) { controller.abort(); }
            controller = ('AbortController' in window) ? new AbortController() : null;
            if (wrap) { wrap.setAttribute('data-busy', '1'); }

            var url = cfg.endpoint + '?catalog_search=1&q=' + encodeURIComponent(state.q)
                + '&page=' + state.page;

            fetch(url, {
                credentials: 'same-origin',
                headers: { 'Accept': 'application/json', 'X-Requested-With': 'fetch' },
                signal: controller ? controller.signal : undefined
            }).then(function (response) {
                if (!response.ok) { throw new Error('HTTP ' + response.status); }
                return response.json();
            }).then(function (data) {
                if (id !== requestId) { return; }
                render(data);
                syncUrl();
            }).catch(function (error) {
                if (error && error.name === 'AbortError') { return; }
                if (id !== requestId) { return; }
                if (meta) { meta.textContent = 'Search failed — press Enter to reload.'; }
            }).finally(function () {
                if (id === requestId && wrap) { wrap.removeAttribute('data-busy'); }
            });
        }

        var search = debounce(function () {
            var value = input.value;
            if (value === state.q) { return; }
            state.q = value;
            state.page = 1;
            fetchPage();
        }, 180);

        input.addEventListener('input', function () { markFilled(); search(); });
        input.addEventListener('search', function () { markFilled(); search(); });
        input.addEventListener('keydown', function (event) {
            if (event.key === 'Escape' && input.value) {
                event.preventDefault();
                input.value = '';
                markFilled();
                search();
            }
        });

        var form = input.closest('form');
        if (form) {
            form.addEventListener('submit', function (event) { event.preventDefault(); search(); });
        }

        if (clear) {
            clear.addEventListener('click', function () {
                input.value = '';
                markFilled();
                input.focus();
                search();
            });
        }

        if (pager) {
            pager.addEventListener('click', function (event) {
                var button = event.target.closest('[data-page-step]');
                if (!button) { return; }
                state.page = Math.min(state.pages, Math.max(1, state.page + Number(button.getAttribute('data-page-step'))));
                fetchPage();
                if (wrap) { wrap.scrollTop = 0; }
                // On phones the list scrolls with the page, not inside the table box.
                if (root.getBoundingClientRect().top < 0) { root.scrollIntoView({ block: 'start' }); }
            });
        }

        markFilled();
        renderPager();

        // "/" focuses the catalog search, the way most admin tools do.
        document.addEventListener('keydown', function (event) {
            if (event.key !== '/' || event.metaKey || event.ctrlKey || event.altKey) { return; }
            var tag = (document.activeElement && document.activeElement.tagName) || '';
            if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') { return; }
            var panel = input.closest('.tab-panel');
            if (panel && !panel.classList.contains('active')) { return; }
            event.preventDefault();
            input.focus();
            input.select();
        });
    })();

    /* --- Promos: status/filter/search list, catalog names and prices, and
       an add/edit modal with a live price preview. Mirrors the device rules:
       percent/amount/fixed change the price; Buy X Take Y keeps it. ------ */
    (function promos() {
        var root = $('[data-promos]');
        if (!root) { return; }

        function pad(n) { return (n < 10 ? '0' : '') + n; }
        function isoDate(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
        function peso(value) {
            return '₱' + Number(value).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        }
        function toNumber(value) {
            var n = parseFloat(String(value == null ? '' : value).replace(/[,\s₱%]/g, ''));
            return isNaN(n) ? null : n;
        }
        // Promo price for a regular price, or null when the promo doesn't change it.
        function promoPrice(promo, regular) {
            var value = toNumber(promo.value);
            if (regular == null || value == null) { return null; }
            if (promo.type === 'percent') { return Math.max(0, regular - Math.round(regular * value) / 100); }
            if (promo.type === 'amount') { return Math.max(0, regular - value); }
            if (promo.type === 'fixed') { return value; }
            return null;
        }
        function dealText(promo) {
            if (promo.type === 'percent') { return (toNumber(promo.value) || 0) + '% OFF'; }
            if (promo.type === 'amount') { return peso(toNumber(promo.value) || 0) + ' OFF'; }
            if (promo.type === 'fixed') { return 'Promo price ' + peso(toNumber(promo.value) || 0); }
            return 'BUY ' + (promo.buy || '?') + ' TAKE ' + (promo.take || '?');
        }

        /* List: statuses by the viewer's own date (the server may sit in
           another time zone), filter chips, search and catalog details. */
        var rows = $$('tr.promo-row', root);
        var today = isoDate(new Date());
        var counts = { all: rows.length, active: 0, scheduled: 0, ended: 0 };
        rows.forEach(function (row) {
            var start = row.getAttribute('data-start'), end = row.getAttribute('data-end');
            var status = end && end < today ? 'ended' : (start && start > today ? 'scheduled' : 'active');
            counts[status]++;
            row.setAttribute('data-promo-status', status);
            var pill = $('[data-promo-status-label]', row);
            if (pill) {
                pill.className = 'status-pill ' + status;
                pill.textContent = status.charAt(0).toUpperCase() + status.slice(1);
            }
        });
        Object.keys(counts).forEach(function (key) {
            var el = $('[data-promo-count="' + key + '"]', root);
            if (el) { el.textContent = counts[key].toLocaleString(); }
        });
        var activeCount = $('[data-promo-active-count]', root);
        if (activeCount) { activeCount.textContent = counts.active.toLocaleString(); }

        var filter = 'all';
        var search = $('[data-promo-search]', root);
        var searchField = search ? search.closest('.search-field') : null;
        var empty = $('[data-promo-empty]', root);
        function applyFilter() {
            var q = search ? search.value.trim().toLowerCase() : '';
            var shown = 0;
            rows.forEach(function (row) {
                var ok = (filter === 'all' || row.getAttribute('data-promo-status') === filter)
                    && (!q || row.textContent.toLowerCase().indexOf(q) !== -1);
                row.hidden = !ok;
                if (ok) { shown++; }
            });
            if (empty && rows.length) {
                empty.hidden = shown > 0;
                $('[data-promo-empty-title]', empty).textContent = 'No promos match';
                $('[data-promo-empty-text]', empty).textContent = 'Try another search or status.';
            }
            if (searchField) { searchField.setAttribute('data-filled', search.value ? '1' : '0'); }
        }
        $$('[data-promo-filter]', root).forEach(function (button) {
            button.addEventListener('click', function () {
                filter = button.getAttribute('data-promo-filter');
                $$('[data-promo-filter]', root).forEach(function (b) { b.classList.toggle('active', b === button); });
                applyFilter();
            });
        });
        if (search) {
            search.addEventListener('input', applyFilter);
            search.addEventListener('keydown', function (event) {
                if (event.key === 'Escape' && search.value) { search.value = ''; applyFilter(); }
            });
            var clearSearch = searchField ? $('[data-search-clear]', searchField) : null;
            if (clearSearch) {
                clearSearch.addEventListener('click', function () { search.value = ''; search.focus(); applyFilter(); });
            }
        }
        applyFilter();

        if (rows.length) {
            fetch(cfg.endpoint + '?promo_products=1', { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
                .then(function (response) { return response.ok ? response.json() : Promise.reject(); })
                .then(function (products) {
                    rows.forEach(function (row) {
                        var info = products[row.getAttribute('data-barcode')];
                        var nameEl = $('[data-promo-name]', row), priceEl = $('[data-promo-price]', row);
                        if (!info) {
                            nameEl.textContent = 'Not in catalog';
                            nameEl.classList.add('missing');
                            nameEl.title = 'This barcode is not in the catalog, so devices will not show this promo.';
                            priceEl.textContent = '—';
                            return;
                        }
                        nameEl.textContent = info.name;
                        var regular = toNumber(info.price);
                        var price = promoPrice(row.dataset, regular);
                        if (regular == null) {
                            priceEl.textContent = '—';
                        } else if (price == null || price >= regular) {
                            priceEl.textContent = peso(regular);
                        } else {
                            priceEl.innerHTML = '<small>' + escapeHtml(peso(regular)) + '</small>' + escapeHtml(peso(price));
                        }
                    });
                })
                .catch(function () {
                    rows.forEach(function (row) { $('[data-promo-name]', row).textContent = '—'; });
                });
        }

        /* Modal */
        var dialog = $('#promo-modal');
        if (!dialog || !supportsDialog()) { return; }
        var form = $('#promo-form', dialog);
        var titleEl = $('[data-promo-modal-title]', dialog);
        var submitBtn = $('[data-promo-submit]', dialog);
        var deleteForm = $('[data-promo-delete-form]', dialog);
        var deleteId = deleteForm ? $('[data-promo-delete-id]', deleteForm) : null;
        var productHint = $('[data-promo-product]', dialog);
        var preview = $('[data-promo-preview]', dialog);
        var valueLabel = $('[data-promo-value-label]', dialog);
        var f = {
            id: $('input[name="id"]', form),
            barcode: $('#promo-barcode', form),
            type: $('#promo-type', form),
            value: $('#promo-value', form),
            buy: $('#promo-buy', form),
            take: $('#promo-take', form),
            start: $('#promo-start', form),
            end: $('#promo-end', form)
        };
        var regularPrice = null;
        var lookupId = 0;

        var autopopen = dialog.hasAttribute('data-autopopen');
        if (dialog.open) { dialog.close(); }

        function current() {
            var label = $('input[name="label"]:checked', form);
            return {
                label: label ? label.value : 'promo',
                type: f.type.value, value: f.value.value, buy: f.buy.value, take: f.take.value
            };
        }

        function refresh() {
            var promo = current();
            var buyTake = promo.type === 'buy_take';
            $$('[data-promo-value-field]', form).forEach(function (el) { el.hidden = buyTake; });
            $$('[data-promo-qty-field]', form).forEach(function (el) { el.hidden = !buyTake; });
            f.value.required = !buyTake;
            f.value.max = promo.type === 'percent' ? '100' : '';
            if (valueLabel) {
                valueLabel.textContent = promo.type === 'percent' ? 'Percent off (%)'
                    : promo.type === 'amount' ? 'Amount off (₱)' : 'Promo price (₱)';
            }
            f.end.min = f.start.value || '';

            var price = promoPrice(promo, regularPrice);
            var tag = promo.label === 'special' ? 'SPECIAL PRICE' : 'PROMO';
            if (buyTake) {
                preview.innerHTML = escapeHtml(tag + ' · ' + dealText(promo))
                    + (regularPrice != null ? ' at ' + escapeHtml(peso(regularPrice)) : '');
            } else if (toNumber(promo.value) == null) {
                preview.textContent = '';
            } else if (regularPrice == null) {
                preview.textContent = tag + ' · ' + dealText(promo);
            } else if (price >= regularPrice) {
                preview.textContent = 'The promo price must be lower than the regular price ('
                    + peso(regularPrice) + '). Devices will ignore it.';
            } else {
                preview.innerHTML = '<s>' + escapeHtml(peso(regularPrice)) + '</s><strong>'
                    + escapeHtml(peso(price)) + '</strong> · save ' + escapeHtml(peso(regularPrice - price));
            }
        }

        var lookup = debounce(function () {
            var code = f.barcode.value.replace(/\s+/g, '');
            var id = ++lookupId;
            regularPrice = null;
            refresh();
            if (!code) { productHint.textContent = ''; productHint.classList.remove('warn'); return; }
            productHint.textContent = 'Looking up…';
            fetch(cfg.endpoint + '?product_lookup=' + encodeURIComponent(code),
                { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
                .then(function (response) { return response.ok ? response.json() : Promise.reject(); })
                .then(function (item) {
                    if (id !== lookupId) { return; }
                    if (item.found) {
                        regularPrice = toNumber(item.price);
                        productHint.classList.remove('warn');
                        productHint.innerHTML = '<strong>' + escapeHtml(item.name) + '</strong> · regular '
                            + escapeHtml(regularPrice != null ? peso(regularPrice) : item.price);
                    } else {
                        productHint.classList.add('warn');
                        productHint.textContent = 'Not found in the catalog. Devices only show promos for catalog items.';
                    }
                    refresh();
                })
                .catch(function () { if (id === lookupId) { productHint.textContent = ''; } });
        }, 300);

        function openFor(values) {
            var editing = !!(values && values.id);
            var start = new Date(), end = new Date();
            end.setDate(end.getDate() + 7);
            f.id.value = editing ? values.id : '';
            f.barcode.value = values ? values.barcode || '' : '';
            f.type.value = values ? values.type || 'percent' : 'percent';
            f.value.value = values ? values.value || '' : '';
            f.buy.value = values && values.buy ? values.buy : '1';
            f.take.value = values && values.take ? values.take : '1';
            f.start.value = values ? values.start || '' : isoDate(start);
            f.end.value = values ? values.end || '' : isoDate(end);
            var label = values && values.label === 'special' ? 'special' : 'promo';
            $$('input[name="label"]', form).forEach(function (radio) { radio.checked = radio.value === label; });
            titleEl.textContent = editing ? 'Edit promo' : 'Add a promo';
            submitBtn.textContent = editing ? 'Save promo' : 'Add promo';
            if (deleteForm) {
                deleteForm.hidden = !editing;
                if (deleteId) { deleteId.value = editing ? values.id : ''; }
            }
            dialog.showModal();
            refresh();
            lookup();
            (editing ? f.value : f.barcode).focus();
        }

        $$('[data-open-add-promo]').forEach(function (button) {
            button.addEventListener('click', function () { openFor(null); });
        });
        root.addEventListener('click', function (event) {
            var link = event.target.closest('[data-edit-promo]');
            var row = event.target.closest('tr.promo-row');
            if (!row) { return; }
            if (!link && (event.target.closest('a, button, input') || String(window.getSelection ? window.getSelection() : ''))) {
                return;
            }
            event.preventDefault();
            openFor(row.dataset);
        });
        f.barcode.addEventListener('input', lookup);
        [f.type, f.value, f.buy, f.take, f.start, f.end].forEach(function (el) {
            el.addEventListener('input', refresh);
            el.addEventListener('change', refresh);
        });
        $$('input[name="label"]', form).forEach(function (radio) { radio.addEventListener('change', refresh); });
        wireModalDismiss(dialog);

        if (autopopen) {
            // Server-rendered edit (?promo_edit=): keep its values, just open it.
            dialog.showModal();
            refresh();
            lookup();
        }
    })();

    /* --- Design: tick-to-manage rows, colour swatch <-> hex sync, and a
       live mock-up. Editing a value ticks its row; unticked rows preview the
       app's defaults, since each scanner keeps its own value for those. --- */
    (function design() {
        var form = $('[data-design]');
        if (!form) { return; }
        var rows = $$('[data-design-row]', form);
        var count = $('[data-design-count]');

        function input(row) { return $('[data-design-input]', row); }
        function isManaged(row) { return $('[data-design-manage]', row).checked; }
        function valueOf(key) {
            var row = $('[data-design-row="' + key + '"]', form);
            if (!row) { return ''; }
            if (!isManaged(row)) { return row.getAttribute('data-default'); }
            var el = input(row);
            if (el.type === 'radio') {
                var picked = $('[data-design-input]:checked', row);
                return picked ? picked.value : row.getAttribute('data-default');
            }
            return el.type === 'checkbox' ? (el.checked ? '1' : '0') : el.value;
        }
        function color(key) {
            var v = String(valueOf(key)).trim();
            if (v && v.charAt(0) !== '#') { v = '#' + v; }
            return /^#[0-9a-f]{6}$/i.test(v) ? v : $('[data-design-row="' + key + '"]', form).getAttribute('data-default');
        }
        function setRowValue(row, value) {
            var el = input(row);
            if (el.type === 'radio') {
                $$('[data-design-input]', row).forEach(function (radio) { radio.checked = radio.value === value; });
                return;
            }
            if (el.type === 'checkbox') { el.checked = value === '1'; } else { el.value = value; }
            var picker = $('[data-color-picker]', row);
            if (picker) { picker.value = value; }
        }

        /* The price check mock-ups, drawn by the same rules as the app's
           PriceScreen: sizes are on a 1280x800 reference screen, scaled to the
           mock's width, and prices are shrunk to fit their space. */
        var measureContext = document.createElement('canvas').getContext('2d');
        function textWidth(text, weight, size) {
            measureContext.font = weight + ' ' + size + 'px Roboto, Arial, sans-serif';
            return measureContext.measureText(text).width;
        }
        function fitSize(text, weight, max, available) {
            var width = textWidth(text, weight, max);
            return width > available && width > 0 ? max * available / width : max;
        }
        function sampleDeal() {
            var end = new Date();
            end.setDate(end.getDate() + 9);
            var date = end.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
            return {
                label: 'PROMO', shortLabel: 'PROMO', was: '₱179.00', now: '₱89.50',
                saved: '₱89.50', save: 'SAVE ₱89.50', bigTop: '50%', bigBottom: 'OFF',
                topIsBig: true, badge: '−50%', extra: '', until: 'Until ' + date, untilDate: date,
                ends: 'Ends in 9 days', bannerTitle: 'PROMO · 50% OFF', bannerDetail: 'Until ' + date,
                color: '#DC2626', ink: '#B91C1C'
            };
        }

        function drawCheck(el, look, withPromo) {
            var width = el.clientWidth || 326;
            var s = width / 1280;
            var deal = withPromo ? sampleDeal() : null;
            var style = deal ? look.promo : null;
            var layout = look.layout;
            var content = {
                name: 'Bear Brand Milk 300g (pc)',
                meta: '4800016644290',
                price: deal ? deal.now : '₱89.50'
            };

            function px(v) { return (v * s).toFixed(2) + 'px'; }
            function div(css, inner) { return '<div style="' + css + '">' + (inner || '') + '</div>'; }
            function label(text, size, weight, color, extra) {
                return div('font-size:' + px(size) + ';font-weight:' + weight + ';color:' + color
                    + ';line-height:1.15;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;' + (extra || ''),
                    escapeHtml(text));
            }
            function nameEl(size, centered, extra) {
                return div('align-self:stretch;font-size:' + px(size) + ';font-weight:500;line-height:1.08;color:' + look.name
                    + ';text-align:' + (centered ? 'center' : 'left')
                    + ';overflow:hidden;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;' + (extra || ''),
                    escapeHtml(content.name));
            }
            function metaEl(centered, top) {
                return label(content.meta, 26, 400, look.meta, 'align-self:stretch;letter-spacing:.1em;margin-top:' + px(top)
                    + ';text-align:' + (centered ? 'center' : 'left'));
            }
            function wasEl(size, top) {
                return label(deal.was, size, 700, look.meta, 'text-decoration:line-through;margin-top:' + px(top));
            }
            function priceEl(ref, available, top) {
                return label(content.price, fitSize(content.price, 900, ref, available), 900, look.price,
                    'line-height:1;letter-spacing:-.02em;overflow:visible;margin-top:' + px(top));
            }
            /* Room kept free at the top-right for a sticker or ribbon. */
            var stickerAt = { center: [240, 40, 51], left: [320, 152, 110], split: [224, 24, 26], price_first: [320, 48, 64] };
            function reserve() {
                if (style === 'ribbon') { return 210; }
                if (style !== 'sticker') { return 0; }
                var g = stickerAt[layout] || stickerAt.center;
                return { center: g[0] + g[2] + 24 - 52, left: g[0] + g[2] + 24 - 104, price_first: g[0] + g[2] + 24 - 104 }[layout] || 0;
            }

            function priceBlock(available, ref, centered, withBar) {
                if (style === 'ribbon') {
                    var side = div('display:flex;flex-direction:column;align-items:flex-start;gap:' + px(14),
                        (deal.was ? wasEl(44, 0) : '')
                        + label(deal.badge, 44, 900, '#fff', 'background:' + deal.color + ';border-radius:' + px(14)
                            + ';padding:' + px(4) + ' ' + px(18)));
                    var sideWidth = Math.max(textWidth(deal.was, 700, 44), textWidth(deal.badge, 900, 44) + 36, 170);
                    if (textWidth(content.price, 900, ref * 0.92) + sideWidth + 36 > available) {
                        // No room beside the price (the Split card): the regular price and the pill go under it.
                        return priceEl(ref * 0.92, available, 40)
                            + div('display:flex;align-items:center;gap:' + px(18) + ';margin-top:' + px(18),
                                (deal.was ? wasEl(44, 0) : '')
                                + label(deal.badge, 44, 900, '#fff', 'background:' + deal.color + ';border-radius:' + px(14)
                                    + ';padding:' + px(4) + ' ' + px(18)));
                    }
                    return div('display:flex;align-items:center;gap:' + px(36) + ';margin-top:' + px(40),
                        priceEl(ref * 0.92, available - sideWidth - 36, 0) + side);
                }
                if (style === 'tag') {
                    return (deal.was ? div('display:flex;align-items:baseline;gap:' + px(14) + ';margin-top:' + px(36),
                        label('WAS', 24, 700, look.meta, 'letter-spacing:.16em') + wasEl(48, 0)) : '')
                        + tag(ref * 0.82, available);
                }
                var struck = deal && deal.was;
                var html = (struck ? wasEl(52, 36) : '') + priceEl(ref, available, struck ? 6 : 48);
                if (withBar && (!style || style === 'classic')) {
                    html += div('width:' + px(centered ? 66 : 96) + ';height:' + px(centered ? 4 : 6)
                        + ';margin-top:' + px(40) + ';background:' + look.price);
                }
                return html;
            }

            function tag(ref, available) {
                var perPixel = textWidth(content.price, 900, ref) / ref;
                var size = Math.min(ref, available / (perPixel + 1.05));
                var b = size * 0.85;
                return div('position:relative;margin-top:' + px(26) + ';transform:rotate(-3deg)',
                    div('background:' + deal.color + ';color:#fff;font-weight:900;font-size:' + px(size)
                        + ';line-height:1;letter-spacing:-.02em;white-space:nowrap;border-radius:' + px(size * 0.175)
                        + ';padding:' + px(size * 0.16) + ' ' + px(size * 0.4) + ' ' + px(size * 0.16) + ' ' + px(size * 0.65)
                        + ';box-shadow:0 ' + px(16) + ' ' + px(32) + ' rgba(15,23,42,.25)', escapeHtml(content.price))
                    + div('position:absolute;left:' + px(size * 0.24) + ';top:50%;width:' + px(size * 0.19) + ';height:'
                        + px(size * 0.19) + ';margin-top:-' + px(size * 0.095) + ';border-radius:50%;background:' + look.bg)
                    + (deal.badge ? div('position:absolute;right:-' + px(b * 0.4) + ';top:-' + px(b * 0.4) + ';width:' + px(b)
                        + ';height:' + px(b) + ';box-sizing:border-box;border-radius:50%;background:#fff;border:'
                        + px(b * 0.045) + ' solid ' + deal.color + ';transform:rotate(14deg);display:flex;align-items:center;'
                        + 'justify-content:center;color:' + deal.ink + ';font-weight:900;font-size:'
                        + px(fitSize(deal.badge, 900, b * 0.3, b * 0.78)), escapeHtml(deal.badge)) : ''));
            }

            function chip(text, fill, outline, color, bold, extra) {
                if (!text) { return ''; }
                return '<span style="white-space:nowrap;font-size:' + px(26) + ';font-weight:' + (bold ? 700 : 500)
                    + ';color:' + color + ';background:' + (fill || 'transparent') + ';'
                    + (outline ? 'border:' + px(3) + ' solid ' + outline + ';' : '')
                    + 'border-radius:999px;padding:' + px(10) + ' ' + px(22) + ';' + (extra || '') + '">'
                    + escapeHtml(text) + '</span>';
            }
            function plain(text, size, weight, color) {
                return text ? '<span style="white-space:nowrap;font-size:' + px(size) + ';font-weight:' + weight
                    + ';color:' + color + '">' + escapeHtml(text) + '</span>' : '';
            }
            function extras(centered) {
                function row(inner, top) {
                    return div('align-self:stretch;display:flex;flex-wrap:wrap;align-items:center;justify-content:'
                        + (centered ? 'center' : 'flex-start') + ';gap:' + px(12) + ' ' + px(14) + ';margin-top:' + px(top), inner);
                }
                switch (style) {
                    case 'sticker':
                        return row(chip(deal.save, deal.color, '', '#fff', true) + chip(deal.extra, '#0F172A', '', '#fff', true)
                            + chip(deal.until, '', 'rgba(15,23,42,.35)', '#1E293B', false), 34);
                    case 'ribbon':
                        return row(chip(deal.save, 'rgba(255,255,255,.7)', '', deal.ink, true)
                            + chip(deal.extra, '#0F172A', '', '#fff', true)
                            + chip(deal.ends, 'rgba(255,255,255,.7)', '', '#1E293B', false), 34);
                    case 'tag':
                        return row(chip(deal.label, '', deal.color, deal.ink, true, 'letter-spacing:.16em')
                            + plain(deal.save, 30, 700, '#1E293B') + chip(deal.extra, '#0F172A', '', '#fff', true)
                            + plain(deal.ends ? '· ' + deal.ends : '', 26, 500, look.meta), 44);
                    case 'poster':
                        return deal.saved ? div('display:flex;align-items:center;gap:' + px(14) + ';margin-top:' + px(30),
                            div('width:' + px(14) + ';height:' + px(14) + ';border-radius:50%;background:' + deal.color)
                            + label('You save ' + deal.saved, 32, 700, deal.ink)) : '';
                    default:
                        return '';
                }
            }

            function banner(shape) {
                var row = shape === 'row';
                var block = shape === 'start' || shape === 'end';
                var align = shape === 'start' ? 'flex-start' : shape === 'end' ? 'flex-end' : 'center';
                return div('box-sizing:border-box;max-width:100%;background:' + deal.color + ';border-radius:' + px(block ? 16 : 18)
                    + ';padding:' + px(20) + ' ' + px(row ? 52 : 28) + ';display:flex;'
                    + (row ? 'align-items:center' : 'flex-direction:column;align-items:' + align),
                    label(deal.bannerTitle, block ? 34 : 40, 900, '#fff', 'letter-spacing:.06em;' + (row ? 'flex:1;min-width:0' : ''))
                    + (deal.bannerDetail ? label(deal.bannerDetail, 24, 500, '#fff', 'opacity:.92;'
                        + (row ? 'margin-left:' + px(24) : 'margin-top:' + px(6))) : ''));
            }

            function sticker() {
                var g = stickerAt[layout] || stickerAt.center;
                var size = g[0];
                var inner = size * 0.68;
                return div('position:absolute;top:' + px(g[1]) + ';right:' + px(g[2]) + ';width:' + px(size) + ';height:' + px(size)
                    + ';border-radius:50%;background:' + deal.color + ';transform:rotate(-12deg);box-shadow:0 ' + px(14) + ' '
                    + px(28) + ' rgba(15,23,42,.28);display:flex;align-items:center;justify-content:center',
                    div('width:' + px(size * 0.89) + ';height:' + px(size * 0.89) + ';box-sizing:border-box;border-radius:50%;border:'
                        + px(size * 0.0125) + ' dashed rgba(255,255,255,.55);display:flex;flex-direction:column;align-items:center;'
                        + 'justify-content:center;text-align:center',
                        label(deal.shortLabel, size * 0.069, 700, '#fff', 'letter-spacing:.25em')
                        + label(deal.bigTop, fitSize(deal.bigTop, 900, size * (deal.topIsBig ? 0.35 : 0.15), inner), 900, '#fff',
                            'line-height:1;overflow:visible')
                        + label(deal.bigBottom, fitSize(deal.bigBottom, 900, size * (deal.topIsBig ? 0.16 : 0.24), inner), 900,
                            '#fff', 'line-height:1.05;overflow:visible')));
            }

            function ribbon() {
                return div('position:absolute;top:0;right:0;width:' + px(560) + ';height:' + px(80) + ';transform:translate('
                    + px(140) + ',' + px(100) + ') rotate(45deg);background:' + deal.color + ';color:#fff;display:flex;'
                    + 'align-items:center;justify-content:center;font-size:'
                    + px(fitSize(deal.label, 900, 28, 280 * 1.414 - 80 * 1.6 - deal.label.length * 28 * 0.28))
                    + ';font-weight:900;letter-spacing:.28em;'
                    + 'box-shadow:0 ' + px(10) + ' ' + px(20) + ' rgba(15,23,42,.25)', escapeHtml(deal.label));
            }

            function posterBlock(blockWidth) {
                var inner = blockWidth - 88;
                return div('width:' + px(blockWidth) + ';flex:none;box-sizing:border-box;padding:' + px(56) + ' ' + px(44) + ' '
                    + px(52) + ';background:' + deal.color + ';display:flex;flex-direction:column;justify-content:space-between',
                    div('', label(deal.label, 24, 700, '#fff', 'letter-spacing:.33em')
                        + div('width:' + px(56) + ';height:' + px(5) + ';margin-top:' + px(16) + ';background:#fff'))
                    + div('', label(deal.bigTop, fitSize(deal.bigTop, 900, deal.topIsBig ? 176 : 76, inner), 900, '#fff',
                            'line-height:.95;overflow:visible')
                        + label(deal.bigBottom, fitSize(deal.bigBottom, 900, deal.topIsBig ? 104 : 112, inner), 900, '#fff',
                            'line-height:1;overflow:visible;margin-top:' + px(4)))
                    + (deal.untilDate ? div('', label('UNTIL', 18, 700, '#fff', 'letter-spacing:.33em;opacity:.85')
                        + label(deal.untilDate, 36, 700, '#fff', 'margin-top:' + px(6))) : ''));
            }

            function stacked(areaWidth, centered) {
                var classic = style === 'classic';
                var side = centered ? 52 : 104;
                var end = centered ? 0 : reserve();
                var around = centered ? reserve() : 0;
                var html = div('position:absolute;inset:0;box-sizing:border-box;display:flex;flex-direction:column;'
                    + 'justify-content:center;align-items:' + (centered ? 'center' : 'flex-start') + ';padding:' + px(32) + ' '
                    + px(side + end) + ' ' + px(32 + (classic ? 150 : 0)) + ' ' + px(side),
                    nameEl(centered ? 64 : 68, centered, around ? 'margin:0 ' + px(around) : '')
                    + metaEl(centered, centered ? 22 : 20)
                    + priceBlock(areaWidth - 2 * side - end, centered ? 168 : 196, centered, true)
                    + extras(centered));
                if (classic) {
                    html += div('position:absolute;left:' + px(52) + ';right:' + px(52) + ';bottom:' + px(52),
                        banner(centered ? 'center' : 'row'));
                }
                return html;
            }
            function split(areaWidth) {
                var pad = 40;
                var cardWidth = Math.round(areaWidth * 0.42);
                var details = div('flex:1;min-width:0;display:flex;flex-direction:column;justify-content:center;'
                    + 'align-items:flex-start;padding-left:' + px(56),
                    nameEl(60, false) + metaEl(false, 22)
                    + (style === 'classic' ? div('margin-top:' + px(48) + ';max-width:100%', banner('start')) : extras(false)));
                var card = div('width:' + px(cardWidth) + ';flex:none;margin-left:' + px(pad) + ';box-sizing:border-box;padding:'
                    + px(28) + ';border-radius:' + px(32) + ';background:rgba(255,255,255,.6);display:flex;flex-direction:column;'
                    + 'align-items:center;justify-content:center', priceBlock(cardWidth - 56, 136, true, true));
                return div('position:absolute;inset:0;box-sizing:border-box;padding:' + px(pad) + ';display:flex', details + card);
            }
            function priceFirst(areaWidth) {
                var side = 104;
                var end = reserve();
                var top = div('flex:1;min-height:0;display:flex;flex-direction:column;justify-content:center;align-items:flex-start;'
                    + 'padding:' + px(24) + ' ' + px(side + end) + ' ' + px(24) + ' ' + px(side),
                    priceBlock(areaWidth - 2 * side - end, 236, false, false) + extras(false));
                var band = div('height:' + px(232) + ';flex:none;box-sizing:border-box;padding:0 ' + px(side)
                    + ';background:rgba(255,255,255,.6);display:flex;align-items:center',
                    div('flex:1;min-width:0;display:flex;flex-direction:column', nameEl(56, false) + metaEl(false, 16))
                    + (style === 'classic' ? div('margin-left:' + px(40), banner('end')) : ''));
                return div('position:absolute;inset:0;display:flex;flex-direction:column', top + band);
            }
            function layoutHtml(which, areaWidth) {
                if (which === 'left') { return stacked(areaWidth, false); }
                if (which === 'split') { return split(areaWidth); }
                if (which === 'price_first') { return priceFirst(areaWidth); }
                return stacked(areaWidth, true);
            }

            var html;
            if (style === 'poster') {
                // The poster block takes the right third; a Split becomes Left aligned beside it.
                var blockWidth = Math.round(1280 * 0.34);
                html = div('position:absolute;inset:0;display:flex',
                    div('position:relative;flex:1;min-width:0', layoutHtml(layout === 'split' ? 'left' : layout, 1280 - blockWidth))
                    + posterBlock(blockWidth));
            } else {
                html = layoutHtml(layout, 1280);
            }
            if (style === 'sticker') { html += sticker(); }
            if (style === 'ribbon') { html += ribbon(); }
            el.style.background = look.bg;
            el.innerHTML = html;
        }

        function refresh() {
            var managed = 0;
            rows.forEach(function (row) {
                var on = isManaged(row);
                row.setAttribute('data-managed', on ? '1' : '0');
                if (on) { managed++; }
                var out = $('[data-range-value]', row);
                if (out) { out.textContent = input(row).value + '%'; }
                $$('.choice-tile', row).forEach(function (tile) {
                    tile.classList.toggle('is-checked', $('input', tile).checked);
                });
            });
            if (count) { count.textContent = managed; }

            var look = {
                layout: valueOf('check_layout'),
                promo: valueOf('promo_style'),
                bg: color('check_bg_color'),
                name: color('check_name_color'),
                meta: color('check_meta_color'),
                price: color('check_price_color')
            };
            drawCheck($('[data-mock-check]', form), look, false);
            drawCheck($('[data-mock-promo]', form), look, true);
            $('[data-mock-scan]', form).style.color = color('scan_prompt_color');
            var marquee = $('[data-mock-marquee]', form);
            marquee.style.background = color('marquee_bg_color');
            marquee.style.color = color('marquee_text_color');
            var scale = Math.max(50, Math.min(250, parseInt(valueOf('logo_scale_percent'), 10) || 100));
            $('[data-mock-logo]', form).style.transform = 'scale(' + (scale / 100) + ')';

            var showLogo = valueOf('video_shows_logo') === '1';
            var name = String(valueOf('store_name')).trim();
            var store = $('[data-mock-store]', form);
            store.textContent = name || 'Store name';
            store.hidden = showLogo || !name;
            $('[data-mock-store-logo]', form).hidden = !showLogo;
            var image = parseInt(valueOf('image_display_seconds'), 10);
            var video = parseInt(valueOf('video_play_seconds'), 10);
            $('[data-mock-timing]', form).textContent = 'Images ' + (image || 12) + 's · videos '
                + (video > 0 ? video + 's' : 'full length');
        }

        rows.forEach(function (row) {
            var manage = $('[data-design-manage]', row);
            var el = input(row);
            var picker = $('[data-color-picker]', row);
            function touched() { manage.checked = true; refresh(); }
            manage.addEventListener('change', refresh);
            if (el.type === 'radio') {
                $$('[data-design-input]', row).forEach(function (radio) {
                    radio.addEventListener('change', touched);
                });
                return;
            }
            el.addEventListener('input', function () {
                if (picker && /^#?[0-9a-f]{6}$/i.test(el.value.trim())) {
                    picker.value = el.value.trim().charAt(0) === '#' ? el.value.trim() : '#' + el.value.trim();
                }
                touched();
            });
            el.addEventListener('change', touched);
            if (picker) {
                picker.addEventListener('input', function () { el.value = picker.value.toUpperCase(); touched(); });
            }
        });

        var defaults = $('[data-design-defaults]', form);
        if (defaults) {
            defaults.addEventListener('click', function () {
                rows.forEach(function (row) {
                    setRowValue(row, row.getAttribute('data-default'));
                    $('[data-design-manage]', row).checked = true;
                });
                refresh();
            });
        }
        var release = $('[data-design-release]', form);
        if (release) {
            release.addEventListener('click', function () {
                rows.forEach(function (row) { $('[data-design-manage]', row).checked = false; });
                refresh();
            });
        }
        refresh();
        // The mock-ups are sized to their width, which is only known once the
        // Design tab is shown and changes with the window.
        var mock = $('[data-mock-check]', form);
        if (window.ResizeObserver && mock) {
            new ResizeObserver(debounce(refresh, 60)).observe(mock);
        } else {
            window.addEventListener('resize', debounce(refresh, 150));
        }
    })();

    /* --- Playback order: live filter, drag-reorder, auto-renumber -------- */
    (function playbackOrder() {
        var list = $('[data-order-list]');
        if (!list) { return; }

        var filter = $('[data-order-filter]');
        var filterField = filter ? filter.closest('.search-field') : null;
        var filterClear = $('[data-order-filter-clear]');
        var meta = $('[data-order-meta]');
        var rows = function () { return $$('.order-row', list); };

        function renumber() {
            rows().forEach(function (row, index) {
                var input = $('.order-input', row);
                if (input) { input.value = String(index + 1); }
            });
        }

        function isFiltered() { return !!(filter && filter.value.trim()); }

        function applyFilter() {
            var needle = filter ? filter.value.trim().toLowerCase() : '';
            if (filterField) { filterField.setAttribute('data-filled', needle ? '1' : '0'); }
            var shown = 0;
            rows().forEach(function (row) {
                var haystack = (row.getAttribute('data-search') || '').toLowerCase();
                var match = !needle || haystack.indexOf(needle) !== -1;
                row.hidden = !match;
                if (match) { shown++; }
            });
            if (meta) {
                var total = rows().length;
                meta.textContent = needle
                    ? shown + ' of ' + total + ' shown'
                    : total + (total === 1 ? ' item' : ' items');
            }
        }

        if (filter) {
            filter.addEventListener('input', applyFilter);
            filter.addEventListener('keydown', function (event) {
                if (event.key === 'Escape' && filter.value) {
                    event.preventDefault();
                    filter.value = '';
                    applyFilter();
                }
            });
        }
        if (filterClear) {
            filterClear.addEventListener('click', function () {
                filter.value = '';
                applyFilter();
                filter.focus();
            });
        }

        // Typing a position re-sorts the list once the field is committed.
        // Skipped while a filter is active so rows do not jump out from under you.
        list.addEventListener('change', function (event) {
            var input = event.target.closest('.order-input');
            if (!input || isFiltered()) { return; }
            var ordered = rows().map(function (row, index) {
                var field = $('.order-input', row);
                var value = parseFloat(field.value);
                return {
                    row: row,
                    pos: isNaN(value) ? index + 1 : value,
                    index: index,
                    // The row you just typed into wins a tie, so "make this 1" puts it first.
                    edited: field === input ? 0 : 1
                };
            }).sort(function (a, b) {
                return (a.pos - b.pos) || (a.edited - b.edited) || (a.index - b.index);
            });

            var frag = document.createDocumentFragment();
            ordered.forEach(function (entry) { frag.appendChild(entry.row); });
            list.appendChild(frag);
            renumber();
            input.focus();
            input.select();
        });

        // Drag to reorder. The handle arms `draggable` on press so the number
        // inputs stay selectable with the mouse.
        var dragged = null;

        $$('.drag-handle', list).forEach(function (handle) {
            var row = handle.closest('.order-row');
            handle.addEventListener('mousedown', function () { row.draggable = true; });
            handle.addEventListener('touchstart', function () { row.draggable = true; }, { passive: true });
            row.addEventListener('dragstart', function (event) {
                dragged = row;
                row.classList.add('dragging');
                if (event.dataTransfer) {
                    event.dataTransfer.effectAllowed = 'move';
                    event.dataTransfer.setData('text/plain', '');
                }
            });
            row.addEventListener('dragend', function () {
                row.classList.remove('dragging');
                row.draggable = false;
                dragged = null;
                $$('.drop-target', list).forEach(function (el) { el.classList.remove('drop-target'); });
                renumber();
            });
        });

        list.addEventListener('dragover', function (event) {
            if (!dragged) { return; }
            event.preventDefault();
            if (event.dataTransfer) { event.dataTransfer.dropEffect = 'move'; }
            var target = event.target.closest('.order-row');
            if (!target || target === dragged || target.hidden) { return; }
            $$('.drop-target', list).forEach(function (el) { el.classList.remove('drop-target'); });
            target.classList.add('drop-target');
            var box = target.getBoundingClientRect();
            var after = event.clientY > box.top + box.height / 2;
            list.insertBefore(dragged, after ? target.nextSibling : target);
        });

        list.addEventListener('drop', function (event) {
            if (dragged) { event.preventDefault(); }
        });

        // Keyboard reordering for anyone not using a pointer.
        list.addEventListener('keydown', function (event) {
            if (!event.altKey) { return; }
            var step = event.key === 'ArrowDown' ? 1 : (event.key === 'ArrowUp' ? -1 : 0);
            if (!step) { return; }
            var row = event.target.closest('.order-row');
            if (!row) { return; }
            event.preventDefault();
            var sibling = step === 1 ? row.nextElementSibling : row.previousElementSibling;
            if (!sibling) { return; }
            list.insertBefore(step === 1 ? sibling : row, step === 1 ? row : sibling);
            renumber();
            event.target.focus();
        });

        applyFilter();
    })();

    /* --- Scanners: live status -------------------------------------------- */
    /* --- Data source: show the fields for the chosen kind, run the live checks --- */
    (function source() {
        var form = $('[data-source-form]');
        if (!form) { return; }
        var kinds = $$('[data-source-kind]', form);

        function kind() {
            var checked = $('[data-source-kind]:checked', form);
            return checked ? checked.value : '';
        }
        function isDatabase(value) { return value !== 'file' && value !== 'url'; }

        function refresh() {
            var current = kind();
            kinds.forEach(function (input) {
                input.closest('.source-kind').classList.toggle('is-checked', input.checked);
            });
            $$('.source-field', form).forEach(function (field) {
                var list = (field.getAttribute('data-kinds') || '').split(' ');
                field.hidden = list.indexOf(current) === -1;
                var labels = field.getAttribute('data-labels');
                var label = $('label[data-default]', field);
                if (labels && label) {
                    var map = JSON.parse(labels);
                    label.textContent = map[current] || label.getAttribute('data-default');
                }
                var placeholders = field.getAttribute('data-placeholders');
                if (placeholders) {
                    $('input', field).placeholder = JSON.parse(placeholders)[current] || '';
                }
            });
            $$('[data-for-kind]', form).forEach(function (note) {
                note.hidden = note.getAttribute('data-for-kind') !== current;
            });
            var tile = $('[data-source-kind]:checked', form);
            var port = tile ? parseInt(tile.closest('.source-kind').getAttribute('data-port'), 10) : 0;
            var portInput = $('[data-source-port]', form);
            if (portInput) { portInput.placeholder = port ? 'Blank = ' + port : ''; }
            $$('.source-db-only', form).forEach(function (part) { part.hidden = !isDatabase(current); });
            var read = $('[data-source-read]:checked', form);
            $$('[data-read]', form).forEach(function (part) {
                part.hidden = !read || part.getAttribute('data-read') !== read.value;
            });
            var windowsAuth = $('[data-source-windows-auth]', form);
            var useWindows = windowsAuth && windowsAuth.checked && current === 'sqlserver';
            $$('[data-source-login]', form).forEach(function (input) { input.disabled = !!useWindows; });
            var schedule = $('[data-source-schedule]', form);
            var daily = $('[data-source-daily]', form);
            if (schedule && daily) { daily.hidden = schedule.value !== 'daily'; }
        }

        form.addEventListener('change', function (event) {
            if (event.target.matches('[data-source-kind], [data-source-read], [data-source-windows-auth], [data-source-schedule]')) {
                refresh();
            }
        });
        refresh();

        function fillList(id, values) {
            var list = document.getElementById(id);
            if (!list) { return; }
            list.innerHTML = values.map(function (value) {
                return '<option value="' + escapeHtml(value) + '">';
            }).join('');
        }

        function table(box, headers, rows) {
            box.innerHTML = '<table class="catalog-table"><thead><tr>' + headers.map(function (name) {
                return '<th>' + escapeHtml(name) + '</th>';
            }).join('') + '</tr></thead><tbody>' + (rows.length ? rows.map(function (row) {
                return '<tr>' + row.map(function (cell) { return '<td>' + escapeHtml(cell) + '</td>'; }).join('') + '</tr>';
            }).join('') : '<tr><td colspan="' + headers.length + '">No rows</td></tr>') + '</tbody></table>';
            box.hidden = false;
        }

        function show(op, ok, message) {
            var box = $('[data-source-result="' + op + '"]', form);
            if (!box) { return; }
            box.textContent = message;
            box.className = 'source-result ' + (ok ? 'is-ok' : 'is-error');
        }

        $$('[data-source-op]', form).forEach(function (button) {
            button.addEventListener('click', function () {
                if (!window.fetch || button.disabled) { return; }
                var op = button.getAttribute('data-source-op');
                var data = new FormData(form);
                data.set('action', 'source_probe');
                data.set('op', op);
                var label = button.innerHTML;
                button.disabled = true;
                button.innerHTML = '<span class="spinner" aria-hidden="true"></span>' + label;
                show(op, true, op === 'test' ? 'Connecting…' : 'Reading…');
                fetch(cfg.endpoint, { method: 'POST', body: data, credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
                    .then(function (response) {
                        return response.json().catch(function () {
                            throw new Error('The portal did not answer as expected. Reload the page (you may need to sign in again).');
                        });
                    })
                    .then(function (result) {
                        show(op, result.ok, result.message || '');
                        if (!result.ok) { return; }
                        if (result.tables) { fillList('source-tables', result.tables); }
                        if (result.columns) { fillList('source-columns', result.columns); }
                        if (op === 'columns') {
                            table($('[data-source-rows]', form), result.columns, result.rows || []);
                            // The server keeps each choice that is still a column and suggests the rest.
                            $$('[data-source-map]', form).forEach(function (input) {
                                input.value = (result.guess || {})[input.getAttribute('data-source-map')] || '';
                            });
                        }
                        if (op === 'preview') {
                            table($('[data-source-preview]', form), ['Barcode', 'Name', 'Unit', 'Category', 'Price', 'Item code'], result.items || []);
                        }
                    })
                    .catch(function (error) { show(op, false, error.message || 'The check failed.'); })
                    .then(function () {
                        button.disabled = false;
                        button.innerHTML = label;
                    });
            });
        });

        // A starting point for SAP Business One: every barcode of every sellable
        // item with its price from price list 1. HANA needs its mixed-case names quoted.
        var example = $('[data-source-example]', form);
        if (example) {
            example.addEventListener('click', function () {
                var q = kind() === 'hana' ? function (name) { return '"' + name + '"'; } : function (name) { return name; };
                var sql = 'SELECT B.' + q('BcdCode') + ' AS barcode, I.' + q('ItemName') + ' AS name, P.' + q('Price') + ' AS price,\n'
                    + '       U.' + q('UomCode') + ' AS uom, G.' + q('ItmsGrpNam') + ' AS category, I.' + q('ItemCode') + ' AS item_code\n'
                    + 'FROM OBCD B\n'
                    + 'JOIN OITM I ON I.' + q('ItemCode') + ' = B.' + q('ItemCode') + '\n'
                    + 'JOIN ITM1 P ON P.' + q('ItemCode') + ' = I.' + q('ItemCode') + ' AND P.' + q('PriceList') + ' = 1\n'
                    + 'LEFT JOIN OUOM U ON U.' + q('UomEntry') + ' = B.' + q('UomEntry') + '\n'
                    + 'LEFT JOIN OITB G ON G.' + q('ItmsGrpCod') + ' = I.' + q('ItmsGrpCod') + '\n'
                    + 'WHERE I.' + q('SellItem') + " = 'Y' AND I." + q('frozenFor') + " = 'N'";
                var query = $('#source-query', form);
                if (query.value.trim() && !window.confirm('Replace the query you typed with the SAP Business One example?')) { return; }
                query.value = sql;
                ['barcode', 'name', 'price', 'uom', 'category', 'item_code'].forEach(function (field) {
                    var input = $('[data-source-map="' + field + '"]', form);
                    if (input) { input.value = field; }
                });
            });
        }

        // Saving with a sync, and Sync now, can take a while on a big database.
        form.addEventListener('submit', function (event) {
            var button = event.submitter;
            if (button && button.value === 'sync') {
                button.innerHTML = '<span class="spinner" aria-hidden="true"></span>Syncing…';
            }
        });
        var syncForm = $('[data-source-sync]');
        if (syncForm) {
            syncForm.addEventListener('submit', function () {
                var button = $('button', syncForm);
                button.innerHTML = '<span class="spinner" aria-hidden="true"></span>Syncing…';
                setTimeout(function () { button.disabled = true; }, 0);
            });
        }
    })();

    (function scanners() {
        var box = $('[data-scanner-status]');
        if (!box || !window.fetch) { return; }
        var refreshing = false;
        function refresh() {
            var panel = box.closest('.tab-panel');
            if (refreshing || document.hidden || (panel && !panel.classList.contains('active'))) { return; }
            refreshing = true;
            fetch(cfg.endpoint + '?scanner_status=1', { credentials: 'same-origin', cache: 'no-store', headers: { 'Accept': 'text/html' } })
                .then(function (response) { return response.ok ? response.text() : Promise.reject(); })
                .then(function (html) {
                    // Only swap in a real status block (a signed-out session returns nothing).
                    if (html.indexOf('data-scanner-rows') !== -1) { box.innerHTML = html; }
                })
                .catch(function () { /* keep showing the last known status */ })
                .then(function () { refreshing = false; });
        }
        refresh();
        document.addEventListener('visibilitychange', refresh);
        setInterval(refresh, 1000);
    })();
})();
