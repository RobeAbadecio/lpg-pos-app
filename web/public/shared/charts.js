// Charts shared by the admin dashboard and the POS summary dashboard: a revenue column chart,
// horizontal bars, and the hover tooltip they use.
(function () {
  const { h, clear, peso, count, compactPeso } = UI;
  const SVG = 'http://www.w3.org/2000/svg';

  let tip = null;
  function tooltip() {
    if (!tip) {
      tip = document.getElementById('tooltip') || h('div', { class: 'tooltip', id: 'tooltip', role: 'tooltip', hidden: true });
      if (!tip.isConnected) document.body.append(tip);
    }
    return tip;
  }

  function showTip(x, y, value, label, meta) {
    const t = tooltip();
    clear(t, h('strong', {}, value), h('div', { class: 't-label' }, label), meta ? h('div', { class: 't-meta' }, meta) : null);
    t.hidden = false;
    const r = t.getBoundingClientRect();
    let left = x + 14, top = y - r.height - 10;
    if (left + r.width > window.innerWidth - 8) left = x - r.width - 14;
    if (top < 8) top = y + 16;
    t.style.left = Math.max(8, left) + 'px';
    t.style.top = top + 'px';
  }
  function hideTip() { tooltip().hidden = true; }

  function fmtDate(iso) {
    const [y, m, d] = iso.split('-').map(Number);
    const dt = new Date(y, m - 1, d);
    return dt.toLocaleDateString('en-PH', { month: 'short', day: 'numeric', year: dt.getFullYear() === new Date().getFullYear() ? undefined : 'numeric' });
  }

  function bucketLabel(key, bucket, long) {
    if (bucket === 'hour') {
      const hr = Number(key);
      const s = (hr % 12 || 12) + (hr < 12 ? ' AM' : ' PM');
      return long ? `Today, ${s}–${((hr + 1) % 12 || 12) + (hr + 1 < 12 || hr + 1 === 24 ? ' AM' : ' PM')}` : s;
    }
    if (bucket === 'month') {
      const [y, m] = key.split('-').map(Number);
      return new Date(y, m - 1, 1).toLocaleDateString('en-PH', long ? { month: 'long', year: 'numeric' } : { month: 'short', year: '2-digit' });
    }
    const [y, m, d] = key.split('-').map(Number);
    return new Date(y, m - 1, d).toLocaleDateString('en-PH', long ? { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' } : { month: 'short', day: 'numeric' });
  }

  function niceTicks(max, target) {
    if (max <= 0) return [0, 1000];
    const raw = max / target;
    const mag = Math.pow(10, Math.floor(Math.log10(raw)));
    const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= raw);
    const ticks = [];
    for (let v = 0; v <= max + step * 0.001; v += step) ticks.push(v);
    if (ticks[ticks.length - 1] < max) ticks.push(ticks[ticks.length - 1] + step);
    return ticks;
  }

  function svg(tag, attrs) {
    const el = document.createElementNS(SVG, tag);
    for (const [k, v] of Object.entries(attrs || {})) el.setAttribute(k, v);
    return el;
  }

  function columnChart(container, series, bucket) {
    const width = Math.max(280, container.clientWidth);
    const height = 240;
    const max = Math.max(0, ...series.map((d) => d.revenue));
    const ticks = niceTicks(max, 4);
    const top = ticks[ticks.length - 1];
    // One unit for the whole axis (₱0 / ₱5K / ₱10K), never a mix of ₱5,000 and ₱10K.
    const unit = top >= 1e6 ? [1e6, 'M'] : top >= 1e4 ? [1e3, 'K'] : [1, ''];
    const tickText = ticks.map((t) => t === 0 ? '₱0' : '₱' + count.format(+(t / unit[0]).toFixed(2)) + unit[1]);
    const left = Math.max(...tickText.map((t) => t.length)) * 7 + 12;
    const m = { top: 20, right: 4, bottom: 26, left };
    const pw = width - m.left - m.right, ph = height - m.top - m.bottom;
    const n = series.length;
    const band = pw / n;
    const barW = Math.max(1, Math.min(24, band - 2, band * 0.72));
    const y = (v) => m.top + ph - (v / top) * ph;

    const root = svg('svg', { viewBox: `0 0 ${width} ${height}`, height, role: 'img', tabindex: 0,
      'aria-label': `Revenue by ${bucket}. Peak ${peso.format(max)}. Use left and right arrow keys to read each ${bucket}.` });

    ticks.forEach((t, i) => {
      const yy = Math.round(y(t)) + 0.5;
      root.append(svg('line', { class: i === 0 ? 'baseline' : 'grid', x1: m.left, x2: width - m.right, y1: yy, y2: yy }));
      const label = svg('text', { class: 'tick', x: m.left - 8, y: yy + 4, 'text-anchor': 'end' });
      label.textContent = tickText[i];
      root.append(label);
    });

    const bars = series.map((d, i) => {
      const cx = m.left + band * i + band / 2;
      const x0 = cx - barW / 2;
      const hgt = d.revenue > 0 ? Math.max(1, (d.revenue / top) * ph) : 0;
      const yTop = m.top + ph - hgt;
      const r = Math.min(4, barW / 2, hgt);
      const path = svg('path', {
        class: 'bar',
        d: hgt ? `M${x0},${m.top + ph} V${yTop + r} Q${x0},${yTop} ${x0 + r},${yTop} H${x0 + barW - r} Q${x0 + barW},${yTop} ${x0 + barW},${yTop + r} V${m.top + ph} Z` : '',
      });
      root.append(path);
      return { path, cx, yTop };
    });

    // Label only the peak, so the chart stays quiet.
    if (max > 0) {
      const iMax = series.findIndex((d) => d.revenue === max);
      const t = svg('text', { class: 'peak', x: bars[iMax].cx, y: bars[iMax].yTop - 6, 'text-anchor': 'middle' });
      t.textContent = compactPeso(max);
      root.append(t);
    } else {
      const t = svg('text', { class: 'empty-msg', x: m.left + pw / 2, y: m.top + ph / 2, 'text-anchor': 'middle' });
      t.textContent = 'No sales in this period';
      root.append(t);
    }

    // X labels: thin out so they never collide; always keep the latest one.
    const stride = Math.max(1, Math.ceil(n / Math.max(1, Math.floor(pw / 58))));
    series.forEach((d, i) => {
      if ((n - 1 - i) % stride !== 0) return;
      const t = svg('text', { class: 'tick', x: bars[i].cx, y: height - 8, 'text-anchor': 'middle' });
      t.textContent = bucketLabel(d.key, bucket, false);
      root.append(t);
    });

    // Hover layer: the whole plot is the hit target; snap to the nearest bucket.
    const hit = svg('rect', { x: m.left, y: m.top, width: pw, height: ph, fill: 'transparent' });
    root.append(hit);
    let active = -1;
    const activate = (i, cx, cy) => {
      active = i;
      container.classList.add('hovering');
      bars.forEach((b, j) => b.path.classList.toggle('active', j === i));
      const d = series[i];
      showTip(cx, cy, peso.format(d.revenue), bucketLabel(d.key, bucket, true),
        `${count.format(d.count)} receipt${d.count === 1 ? '' : 's'} · ${count.format(d.qty)} cylinder${d.qty === 1 ? '' : 's'}`);
    };
    const deactivate = () => { active = -1; container.classList.remove('hovering'); bars.forEach((b) => b.path.classList.remove('active')); hideTip(); };
    const indexAt = (clientX) => {
      const box = root.getBoundingClientRect();
      const x = (clientX - box.left) * (width / box.width);
      return Math.min(n - 1, Math.max(0, Math.floor((x - m.left) / band)));
    };
    hit.addEventListener('pointermove', (e) => activate(indexAt(e.clientX), e.clientX, e.clientY));
    hit.addEventListener('pointerleave', deactivate);
    const keyPoint = (i) => {
      const box = root.getBoundingClientRect();
      const scale = box.width / width;
      return [box.left + bars[i].cx * scale, box.top + Math.min(bars[i].yTop, m.top + ph - 10) * scale];
    };
    root.addEventListener('focus', () => { const i = n - 1; activate(i, ...keyPoint(i)); });
    root.addEventListener('blur', deactivate);
    root.addEventListener('keydown', (e) => {
      if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
      e.preventDefault();
      const i = Math.min(n - 1, Math.max(0, (active < 0 ? n - 1 : active) + (e.key === 'ArrowRight' ? 1 : -1)));
      activate(i, ...keyPoint(i));
    });

    clear(container, root);
  }

  function hbars(container, rows, meta, empty) {
    if (!rows.length) { clear(container, h('div', { class: 'empty' }, empty)); return; }
    const max = Math.max(...rows.map((r) => r.revenue)) || 1;
    clear(container, h('div', { class: 'hbars' }, rows.map((r) => {
      const fill = h('div', { class: 'hbar-fill' });
      fill.style.width = `calc((100% - 96px) * ${(r.revenue / max).toFixed(4)})`;
      const row = h('div', { class: 'hbar', tabindex: 0 },
        h('div', { class: 'hbar-label' }, h('div', { class: 'name', title: r.label }, r.label), h('div', { class: 'meta' }, meta(r))),
        h('div', { class: 'hbar-track' }, fill, h('span', { class: 'hbar-value' }, peso.format(r.revenue))));
      const show = (x, y) => showTip(x, y, peso.format(r.revenue), r.label, meta(r));
      row.addEventListener('pointermove', (e) => show(e.clientX, e.clientY));
      row.addEventListener('pointerleave', hideTip);
      row.addEventListener('focus', () => { const b = fill.getBoundingClientRect(); show(b.right, b.top); });
      row.addEventListener('blur', hideTip);
      return row;
    })));
  }

  // Change against the previous period. upIsBad: money paid out, where going up is not good news.
  function delta(el, cur, prev, compare, upIsBad) {
    clear(el);
    if (prev == null || cur == null) return;
    if (prev === 0 && cur === 0) { el.append('None in either period'); return; }
    if (prev === 0) { el.append(h('span', { class: 'up' }, '▲ New'), ' ', compare); return; }
    const pct = ((cur - prev) / prev) * 100;
    const flat = Math.abs(pct) < 0.5;
    const good = upIsBad ? pct < 0 : pct > 0;
    el.append(flat ? h('span', {}, '■ No change') : h('span', { class: good ? 'up' : 'down' }, `${pct > 0 ? '▲' : '▼'} ${Math.abs(pct).toFixed(Math.abs(pct) < 10 ? 1 : 0)}%`), ' ', compare);
  }

  // Tanks per size: with load, empty, at the refiller, and all of them together.
  function sizeTable(container, sizes) {
    if (!sizes.length) { clear(container, h('div', { class: 'empty' }, 'No products yet.')); return; }
    const kg = (w) => (Number.isInteger(w) ? w.toFixed(1) : String(w)) + ' kg';
    clear(container, h('table', { class: 'size-table' },
      h('thead', {}, h('tr', {}, ['Size', 'With load', 'Empty', 'At refiller', 'Total tanks'].map((t, i) => h('th', { class: i ? 'right' : null }, t)))),
      h('tbody', {}, sizes.map((s) => h('tr', {},
        h('td', {}, kg(s.weight)),
        h('td', { class: 'right num' }, count.format(s.loaded)),
        h('td', { class: 'right num' }, count.format(s.empty)),
        h('td', { class: 'right num' }, count.format(s.atRefiller)),
        h('td', { class: 'right num' }, count.format(s.loaded + s.empty + s.atRefiller))))),
      h('tfoot', {}, h('tr', {},
        h('td', {}, 'All sizes'),
        h('td', { class: 'right num' }, count.format(sizes.reduce((n, s) => n + s.loaded, 0))),
        h('td', { class: 'right num' }, count.format(sizes.reduce((n, s) => n + s.empty, 0))),
        h('td', { class: 'right num' }, count.format(sizes.reduce((n, s) => n + s.atRefiller, 0))),
        h('td', { class: 'right num' }, count.format(sizes.reduce((n, s) => n + s.loaded + s.empty + s.atRefiller, 0)))))));
  }

  window.Charts = { columnChart, hbars, delta, sizeTable, bucketLabel, fmtDate, hideTip };
})();
