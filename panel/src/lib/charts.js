/* Drawing.
 *
 * Canvas, by hand. The panel ships inside a jar with no external assets, and a
 * charting library would be an order of magnitude more code than the shapes
 * this console actually needs: a tick ribbon, a line, and a row of bars. */

import { t } from './i18n.js';
import { span } from './format.js';

export const COLOR = {
  slate: '#39434F',
  ash: '#8A95A6',
  dim: '#5E6878',
  vein: '#2A3440',
  lichen: '#7FB069',
  amber: '#E0A458',
  redstone: '#D64550',
  lapis: '#5B8BD0',
  violet: '#9A7FD0',
  teal: '#5FB0A8',
  calcite: '#E6EBF2',
  void: '#0E1116',
};

/** A tick is nominally 50 ms. These are where it stops being fine. */
export const TICK_WARN = 75;
export const TICK_BAD = 120;

function fit(canvas) {
  const ratio = window.devicePixelRatio || 1;
  const width = canvas.clientWidth || canvas.width;
  const height = canvas.clientHeight || canvas.height;
  const wantW = Math.max(1, Math.round(width * ratio));
  const wantH = Math.max(1, Math.round(height * ratio));
  if (canvas.width !== wantW || canvas.height !== wantH) {
    canvas.width = wantW;
    canvas.height = wantH;
  }
  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  return { ctx, width, height };
}

function tickColor(ms) {
  if (ms >= TICK_BAD) return COLOR.redstone;
  if (ms >= TICK_WARN) return COLOR.amber;
  return COLOR.slate;
}

/** Hex to rgba, for fills that sit under a line without competing with it. */
function alpha(hex, opacity) {
  const value = parseInt(hex.slice(1), 16);
  return `rgba(${(value >> 16) & 255}, ${(value >> 8) & 255}, ${value & 255}, ${opacity})`;
}

/**
 * The tick ribbon: one bar per tick, height by duration.
 *
 * Monochrome until something is wrong. A healthy server draws an even slate
 * comb that recedes into the chrome, so a single overrun tick is the only
 * coloured thing on the page -- which is what makes it findable at a glance
 * rather than one more blinking indicator to learn to ignore.
 */
export function drawRibbon(canvas, ticks, options = {}) {
  if (!canvas) return;
  const { ctx, width, height } = fit(canvas);
  const gap = options.gap ?? 1;
  const barWidth = options.barWidth ?? 3;
  const step = barWidth + gap;
  const capacity = Math.max(1, Math.floor(width / step));
  const shown = ticks.slice(-capacity);

  ctx.clearRect(0, 0, width, height);
  if (shown.length === 0) return;

  // The 50 ms line is a tick's whole budget. Drawing it means the ribbon can be
  // read without knowing the scale: below the line the server is keeping up.
  const ceiling = Math.max(TICK_BAD, ...shown);
  const y50 = height - (50 / ceiling) * height;

  ctx.strokeStyle = COLOR.vein;
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(0, Math.round(y50) + 0.5);
  ctx.lineTo(width, Math.round(y50) + 0.5);
  ctx.stroke();

  const offset = width - shown.length * step;
  shown.forEach((ms, index) => {
    const barHeight = Math.max(1, (Math.min(ms, ceiling) / ceiling) * height);
    ctx.fillStyle = tickColor(ms);
    ctx.fillRect(offset + index * step, height - barHeight, barWidth, barHeight);
  });
}

/**
 * A line chart.
 *
 * @param series  [{ points: [{t, v}], color, label, band: [{t, lo, hi}] }]
 * @param options { min, max, decimals, unit, area, hoverX, gridlines }
 * @returns the plot geometry, so the caller can turn a pointer position back
 *          into a timestamp for tooltips. Null when there was nothing to draw.
 */
export function drawLine(canvas, series, options = {}) {
  if (!canvas) return null;
  const { ctx, width, height } = fit(canvas);
  ctx.clearRect(0, 0, width, height);

  const padLeft = options.padLeft ?? 38;
  const padBottom = 16;
  const padTop = 8;
  const plotW = Math.max(1, width - padLeft - 6);
  const plotH = Math.max(1, height - padTop - padBottom);

  const all = series.flatMap((s) => s.points ?? []);
  if (all.length === 0) {
    ctx.fillStyle = COLOR.dim;
    ctx.font = '11px ui-monospace, monospace';
    ctx.textAlign = 'center';
    ctx.fillText(t('metrics.empty'), width / 2, height / 2);
    return null;
  }

  let minT = Infinity;
  let maxT = -Infinity;
  let lowV = Infinity;
  let highV = -Infinity;
  for (const point of all) {
    if (point.t < minT) minT = point.t;
    if (point.t > maxT) maxT = point.t;
    if (point.v < lowV) lowV = point.v;
    if (point.v > highV) highV = point.v;
  }
  // A band widens the domain, or the shaded area would clip against the frame.
  for (const line of series) {
    for (const point of line.band ?? []) {
      if (point.lo < lowV) lowV = point.lo;
      if (point.hi > highV) highV = point.hi;
    }
  }

  const minV = options.min ?? lowV;
  const maxV = options.max ?? highV;
  const spanT = Math.max(1, maxT - minT);
  const spanV = Math.max(1e-6, maxV - minV);

  const x = (time) => padLeft + ((time - minT) / spanT) * plotW;
  const y = (value) => padTop + plotH
    - (Math.min(Math.max(value, minV), maxV) - minV) / spanV * plotH;

  // Three gridlines by default. More is chart furniture; fewer and the reader
  // cannot tell whether a dip went to 19 or to 12.
  const lines = options.gridlines ?? 2;
  ctx.font = '10px ui-monospace, monospace';
  ctx.textAlign = 'right';
  ctx.textBaseline = 'middle';
  for (let i = 0; i <= lines; i += 1) {
    const value = minV + (spanV * i) / lines;
    const gy = Math.round(y(value)) + 0.5;
    ctx.strokeStyle = COLOR.vein;
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(padLeft, gy);
    ctx.lineTo(width - 6, gy);
    ctx.stroke();
    ctx.fillStyle = COLOR.dim;
    ctx.fillText(label(value, options.decimals ?? 0), padLeft - 6, gy);
  }

  for (const line of series) {
    const points = line.points ?? [];
    if (points.length === 0) continue;
    const colour = line.color ?? COLOR.lapis;

    // The min/max band, when the caller asked for it. This is the honest part of
    // an averaged chart: a five-minute bucket that averaged 19.8 TPS but touched
    // 4 is not the same server as one that never left 19.8.
    if (line.band?.length) {
      ctx.beginPath();
      line.band.forEach((point, index) => {
        const px = x(point.t);
        const py = y(point.hi);
        if (index === 0) ctx.moveTo(px, py);
        else ctx.lineTo(px, py);
      });
      for (let i = line.band.length - 1; i >= 0; i -= 1) {
        ctx.lineTo(x(line.band[i].t), y(line.band[i].lo));
      }
      ctx.closePath();
      ctx.fillStyle = alpha(colour, 0.13);
      ctx.fill();
    }

    if (options.area) {
      ctx.beginPath();
      ctx.moveTo(x(points[0].t), padTop + plotH);
      for (const point of points) ctx.lineTo(x(point.t), y(point.v));
      ctx.lineTo(x(points[points.length - 1].t), padTop + plotH);
      ctx.closePath();
      const fill = ctx.createLinearGradient(0, padTop, 0, padTop + plotH);
      fill.addColorStop(0, alpha(colour, 0.22));
      fill.addColorStop(1, alpha(colour, 0));
      ctx.fillStyle = fill;
      ctx.fill();
    }

    ctx.beginPath();
    points.forEach((point, index) => {
      const px = x(point.t);
      const py = y(point.v);
      if (index === 0) ctx.moveTo(px, py);
      else ctx.lineTo(px, py);
    });
    ctx.strokeStyle = colour;
    ctx.lineWidth = 1.5;
    ctx.lineJoin = 'round';
    ctx.stroke();
  }

  // The crosshair. Drawn last so it sits over every series.
  if (options.hoverX !== null && options.hoverX !== undefined) {
    const hx = Math.min(padLeft + plotW, Math.max(padLeft, options.hoverX));
    ctx.strokeStyle = COLOR.ash;
    ctx.lineWidth = 1;
    ctx.setLineDash([2, 3]);
    ctx.beginPath();
    ctx.moveTo(Math.round(hx) + 0.5, padTop);
    ctx.lineTo(Math.round(hx) + 0.5, padTop + plotH);
    ctx.stroke();
    ctx.setLineDash([]);

    const time = minT + ((hx - padLeft) / plotW) * spanT;
    for (const line of series) {
      const point = nearest(line.points ?? [], time);
      if (!point) continue;
      ctx.fillStyle = line.color ?? COLOR.lapis;
      ctx.beginPath();
      ctx.arc(x(point.t), y(point.v), 3, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  // Time is labelled at the ends only. A column of timestamps under a
  // 120-pixel chart is unreadable, and "an hour ago, to now" is the question.
  ctx.fillStyle = COLOR.dim;
  ctx.textBaseline = 'alphabetic';
  ctx.textAlign = 'left';
  ctx.fillText(span(maxT - minT), padLeft, height - 3);
  ctx.textAlign = 'right';
  ctx.fillText(t('metrics.now'), width - 6, height - 3);

  return { padLeft, padTop, plotW, plotH, width, height, minT, maxT, minV, maxV };
}

/**
 * A row of labelled bars, for a tally rather than a trend.
 *
 * Horizontal because the labels are plugin names: vertical bars would need
 * rotated text, and rotated text is a thing people squint at.
 */
export function drawBars(canvas, entries, options = {}) {
  if (!canvas) return;
  const { ctx, width, height } = fit(canvas);
  ctx.clearRect(0, 0, width, height);

  if (entries.length === 0) {
    ctx.fillStyle = COLOR.dim;
    ctx.font = '11px ui-monospace, monospace';
    ctx.textAlign = 'center';
    ctx.fillText(t('metrics.empty'), width / 2, height / 2);
    return;
  }

  const labelWidth = options.labelWidth ?? 110;
  const rowHeight = Math.min(22, height / entries.length);
  const barHeight = Math.max(4, rowHeight - 8);
  const plotW = Math.max(1, width - labelWidth - 44);
  const top = Math.max(...entries.map((e) => e.value), 1);

  ctx.font = '11px ui-monospace, monospace';
  ctx.textBaseline = 'middle';

  entries.forEach((entry, index) => {
    const cy = index * rowHeight + rowHeight / 2;

    ctx.textAlign = 'right';
    ctx.fillStyle = COLOR.ash;
    ctx.fillText(clip(ctx, entry.label, labelWidth - 8), labelWidth - 8, cy);

    ctx.fillStyle = COLOR.vein;
    ctx.fillRect(labelWidth, cy - barHeight / 2, plotW, barHeight);

    ctx.fillStyle = entry.color ?? COLOR.lapis;
    ctx.fillRect(labelWidth, cy - barHeight / 2,
      Math.max(2, (entry.value / top) * plotW), barHeight);

    ctx.textAlign = 'left';
    ctx.fillStyle = COLOR.calcite;
    ctx.fillText(String(entry.value), labelWidth + plotW + 8, cy);
  });
}

/** Truncates a label to fit, with an ellipsis, rather than letting it overlap. */
function clip(ctx, text, maxWidth) {
  if (ctx.measureText(text).width <= maxWidth) return text;
  let cut = text;
  while (cut.length > 1 && ctx.measureText(`${cut}…`).width > maxWidth) {
    cut = cut.slice(0, -1);
  }
  return `${cut}…`;
}

function nearest(points, time) {
  if (points.length === 0) return null;
  let best = points[0];
  let bestDelta = Math.abs(best.t - time);
  for (const point of points) {
    const delta = Math.abs(point.t - time);
    if (delta < bestDelta) {
      best = point;
      bestDelta = delta;
    }
  }
  return best;
}

/** Exported so a tooltip can name the value under the pointer. */
export function pointAt(series, layout, hoverX) {
  if (!layout) return [];
  const time = layout.minT
    + ((Math.min(layout.padLeft + layout.plotW, Math.max(layout.padLeft, hoverX))
      - layout.padLeft) / layout.plotW) * (layout.maxT - layout.minT);
  return series
    .map((line) => ({ line, point: nearest(line.points ?? [], time) }))
    .filter((entry) => entry.point);
}

function label(value, decimals) {
  if (Math.abs(value) >= 10000) return `${Math.round(value / 1000)}k`;
  return value.toFixed(decimals);
}

/** Turns a /api/series entry into chart points. */
export function toPoints(series) {
  if (!series || !series.t) return [];
  return series.t.map((time, i) => ({ t: time, v: series.avg[i] }));
}

/** The same entry as a min/max band, for showing what an average hides. */
export function toBand(series) {
  if (!series || !series.t || !series.min || !series.max) return [];
  return series.t.map((time, i) => ({ t: time, lo: series.min[i], hi: series.max[i] }));
}
